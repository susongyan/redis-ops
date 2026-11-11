package io.github.susongyan.redisops.worker.runtime;

import io.github.susongyan.redisops.worker.protocol.RdbEvent;
import io.github.susongyan.redisops.worker.protocol.RespProtocolException;
import io.github.susongyan.redisops.worker.persistence.WorkerSyncExecutionPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Rebuilds one oversized key element-by-element on the target through a private temporary key
 * (design doc §6/§7). All callbacks run inline on the single parse thread — element order must be
 * strict, so batches never enter the restore pool's shared lanes. Every batch rides the standard
 * WATCH-fence transaction; the finalize batch is a pure RENAME(+PEXPIREAT) so the target switches
 * from the complete old value to the complete new value atomically.
 *
 * <p>
 * Failure semantics (§7.3): connection-class batch failures reconnect and retry three times,
 * with LLEN reconciliation for RPUSH batches (the only non-idempotent append). A target OOM is
 * never retried — the temporary key is unlinked and the raw error surfaces. A single element over
 * the target bulk limit aborts the split and turns the key into a skip with an alert.
 */
final class SplitKeyRestoreWriter implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SplitKeyRestoreWriter.class);
    /** RPUSH/SADD/ZADD/HSET only; the parser never emits other types on the split path. */
    private static final int T_LIST = 1, T_SET = 2, T_ZSET = 3, T_HASH = 4, T_ZSET_2 = 5,
            T_HASH_ZIPMAP = 9, T_LIST_ZIPLIST = 10, T_SET_INTSET = 11, T_HASH_ZIPLIST = 13,
            T_LIST_QUICKLIST = 14, T_HASH_LISTPACK = 16, T_LIST_QUICKLIST_2 = 18, T_SET_LISTPACK = 20;

    private final SplitTargetChannel target;
    private final long taskId;
    private final String channel;
    private final TargetFence fence;
    private final LeaseGuard leaseGuard;
    private final SplitKeySettings settings;
    private final Runnable beforeApply;
    private final Runnable afterApply;
    private final FullSyncProgressTracker progress;
    private final WorkerSyncExecutionPort sync;
    private final Counter splitKeys;
    private final Counter splitElements;
    private final Counter splitBatches;
    private final Counter skippedOversize;
    private final Counter skippedUnsupported;

    private long bulkLimit = -1;
    private boolean bulkLimitProbed;

    // Per-key state, written only by the parse thread; volatile for the close() reader.
    private volatile byte[] currentKey;
    private volatile byte[] tempKey;
    private int rdbType;
    // RDB-declared count from the begin event (0 for quicklists: they declare node counts);
    // only the bulk-limit abort still reports it — hard-cap skips carry the parser's exact sum
    private long estimatedElements;
    private long expireAt = -1;
    private long elementsWritten;
    private boolean listKind;
    private boolean passive; // filtered-out key: swallow elements until the end event
    private boolean aborted; // element over bulk limit: skip trio emitted, swallow the remainder
    private List<byte[][]> batch = new ArrayList<>();
    private long batchBytes;

    SplitKeyRestoreWriter(SplitTargetChannel target, long taskId, String channel, TargetFence fence,
            LeaseGuard leaseGuard, SplitKeySettings settings, Runnable beforeApply, Runnable afterApply,
            FullSyncProgressTracker progress, WorkerSyncExecutionPort sync, MeterRegistry meters) {
        this.target = target;
        this.taskId = taskId;
        this.channel = channel;
        this.fence = fence;
        this.leaseGuard = leaseGuard;
        this.settings = settings;
        this.beforeApply = beforeApply;
        this.afterApply = afterApply;
        this.progress = progress;
        this.sync = sync;
        this.splitKeys = meters.counter("sync_split_keys_total", "channel", channel);
        this.splitElements = meters.counter("sync_split_elements_total", "channel", channel);
        this.splitBatches = meters.counter("sync_split_batches_total", "channel", channel);
        this.skippedOversize = meters.counter("sync_skipped_oversize_keys_total", "channel", channel);
        this.skippedUnsupported = meters.counter("sync_skipped_unsupported_keys_total", "channel", channel);
    }

    /** Opens a split key: clears any crash residue, then records the key's metadata. */
    void begin(RdbEvent.BigKeyValueBegin event) throws IOException {
        if (currentKey != null || passive)
            throw new IllegalStateException("split begin inside an open split key");
        currentKey = event.key();
        tempKey = target.tempKey(taskId, channel, currentKey);
        rdbType = event.rdbType();
        estimatedElements = switch (rdbType) { // quicklists declare a node count, not elements —
            case T_LIST_QUICKLIST, T_LIST_QUICKLIST_2 -> 0; // reporting nodes as elements would lie
            default -> event.estimatedElements();
        };
        expireAt = event.absoluteExpireMillis();
        elementsWritten = 0;
        listKind = switch (rdbType) {
            case T_LIST, T_LIST_ZIPLIST, T_LIST_QUICKLIST, T_LIST_QUICKLIST_2 -> true;
            default -> false;
        };
        log.warn("split key start taskId={} keyBytes={} keyFingerprint={} rdbType={} estimatedElements={}",
                taskId, currentKey.length, fingerprint(currentKey), rdbType, event.estimatedElements());
        target.unlink(tempKey); // deterministic name: a crashed attempt leaves its copy behind
        progress.splittingStart();
    }

    /** Marks the current key as filtered out; events are swallowed until its end arrives. */
    void skipPassively() {
        if (currentKey != null || passive)
            throw new IllegalStateException("passive skip inside an open split key");
        passive = true;
    }

    void element(RdbEvent.KeyElement event) throws IOException {
        if (passive || aborted)
            return;
        if (currentKey == null)
            throw new IllegalStateException("split element outside a split key");
        long largest = Math.max(event.member().length,
                Math.max(event.value() == null ? 0 : event.value().length,
                        event.score() == null ? 0 : event.score().length));
        if (largest > bulkLimit()) { // §5.2 invariant backstop: this element can never be sent
            abortToList("ELEMENT_OVER_BULK_LIMIT", event.member().length, elementsWritten, estimatedElements);
            aborted = true; // swallow the remaining elements; the end event resets the state
            return;
        }
        byte[][] command = appendCommand(rdbType, tempKey, event);
        long commandBytes = commandBytes(command);
        if (!batch.isEmpty() && batchBytes + commandBytes > settings.splitBatchBytes())
            flushBatch();
        batch.add(command);
        batchBytes += commandBytes;
        if (batchBytes >= settings.splitBatchBytes() || batch.size() >= settings.splitBatchCommands())
            flushBatch();
    }

    /** Flushes the tail and atomically renames the rebuilt value into place. */
    void end(RdbEvent.BigKeyValueEnd event) throws IOException {
        if (passive) {
            passive = false;
            return;
        }
        if (aborted) { // the skip trio was already emitted where the abort happened
            resetKeyState();
            progress.splittingEnd();
            return;
        }
        if (currentKey == null)
            throw new IllegalStateException("split end outside a split key");
        flushBatch();
        finalizeKey();
        splitKeys.increment();
        splitElements.increment(elementsWritten);
        progress.splitKeyDone(event.payloadBytes());
        log.warn("split key end taskId={} keyBytes={} keyFingerprint={} rdbType={} elements={} payloadBytes={}",
                taskId, currentKey.length, fingerprint(currentKey), rdbType, elementsWritten,
                event.payloadBytes());
        resetKeyState();
        progress.splittingEnd();
    }

    /** Emits the skip trio (log, counter, task event) and aborts an open split if one exists. */
    void skipped(RdbEvent.SkippedKey event) throws IOException {
        if (passive) { // filtered-out key: the user never asked for it, no alert
            passive = false;
            return;
        }
        String reason = event.reason() == RdbEvent.SkipReason.UNSUPPORTED_TYPE
                ? "UNSUPPORTED_TYPE"
                : "OVER_HARD_CAP";
        if (currentKey != null) { // mid-stream hard-cap abort: no end event follows, reset fully
            abortToList(reason, event.payloadBytes(), elementsWritten, event.estimatedElements());
            resetKeyState();
            progress.splittingEnd();
            return;
        }
        emitSkip(event.key(), event.rdbType(), event.payloadBytes(), event.estimatedElements(), reason);
    }

    @Override
    public synchronized void close() {
        if (currentKey != null && tempKey != null) { // stopped mid-key: leave no half-written temp key
            try {
                target.unlink(tempKey);
            } catch (Exception failure) {
                log.warn("cannot unlink temporary split key on close taskId={} keyFingerprint={} error={}",
                        taskId, fingerprint(currentKey), String.valueOf(failure.getMessage()));
            }
        }
        target.close();
    }

    // -----------------------------------------------------------------------------------------

    private void flushBatch() throws IOException {
        if (batch.isEmpty())
            return;
        List<byte[][]> pending = batch;
        batch = new ArrayList<>();
        long bytes = batchBytes;
        batchBytes = 0;
        beforeApply.run();
        try {
            applyWithReconciliation(pending);
            splitBatches.increment();
        } finally {
            afterApply.run();
        }
    }

    /**
     * Applies one batch, reconnecting up to three times. RPUSH is not idempotent, so after a
     * failed attempt the temp key's length is reconciled against the confirmed prefix: equal means
     * the batch never landed (safe retry), prefix plus this batch means it did (count as applied —
     * re-sending would duplicate elements), anything else or an unreadable length fails the task
     * rather than guessing. Set, zset and hash appends are element-idempotent and retry blindly.
     * A target OOM is rethrown as-is.
     */
    private void applyWithReconciliation(List<byte[][]> pending) throws IOException {
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            leaseGuard.assertValid();
            try {
                target.apply(tempKey, pending, fence, leaseGuard);
                elementsWritten += pending.size();
                return;
            } catch (IOException | RespProtocolException error) {
                if (FullRestorePool.isTargetOOM(error)) {
                    unlinkTempBestEffort();
                    throw error; // every retry of the same batch would be rejected again
                }
                last = error;
                target.reopen(tempKey);
                if (listKind) {
                    long length;
                    try {
                        length = target.llen(tempKey);
                    } catch (IOException | RespProtocolException probeFailure) {
                        throw new IOException("cannot reconcile split list length for "
                                + fingerprint(currentKey), probeFailure);
                    }
                    if (length == elementsWritten + pending.size()) { // EXEC landed, reply lost
                        elementsWritten += pending.size();
                        return;
                    }
                    if (length != elementsWritten)
                        throw new IOException("split list length " + length + " matches neither the "
                                + "confirmed prefix " + elementsWritten + " nor the pending batch of "
                                + pending.size() + " for " + fingerprint(currentKey), last);
                }
            }
        }
        throw new IOException("split key batch remained unavailable for " + fingerprint(currentKey), last);
    }

    private void finalizeKey() throws IOException {
        List<byte[][]> finalize = new ArrayList<>(2);
        finalize.add(new byte[][]{bytes("RENAME"), tempKey, currentKey});
        if (expireAt > 0)
            finalize.add(new byte[][]{bytes("PEXPIREAT"), currentKey,
                    bytes(Long.toString(expireAt))});
        beforeApply.run();
        try {
            Exception last = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                leaseGuard.assertValid();
                try {
                    target.apply(tempKey, finalize, fence, leaseGuard);
                    return;
                } catch (IOException | RespProtocolException error) {
                    if (FullRestorePool.isTargetOOM(error)) {
                        unlinkTempBestEffort();
                        throw error;
                    }
                    last = error;
                    target.reopen(tempKey);
                    if (settledWithoutReply()) // the rename landed but the reply was lost
                        return;
                }
            }
            throw new IOException("split key finalize remained unavailable for "
                    + fingerprint(currentKey), last);
        } finally {
            afterApply.run();
        }
    }

    /**
     * True when the temp key is gone while the finalize transaction was in flight — the rename
     * already happened. PEXPIREAT may have been lost with it, so the caller re-applies it
     * idempotently inside the same fenced transaction form.
     */
    private boolean settledWithoutReply() throws IOException {
        long exists;
        try {
            exists = target.exists(tempKey);
        } catch (IOException | RespProtocolException probeFailure) {
            return false;
        }
        if (exists != 0)
            return false;
        if (expireAt > 0) {
            try {
                byte[][] expire = {bytes("PEXPIREAT"), currentKey, bytes(Long.toString(expireAt))};
                target.apply(tempKey, java.util.Collections.singletonList(expire), fence, leaseGuard);
            } catch (IOException | RespProtocolException expireFailure) {
                log.warn("cannot re-apply PEXPIREAT after split finalize taskId={} keyFingerprint={} error={}",
                        taskId, fingerprint(currentKey), String.valueOf(expireFailure.getMessage()));
            }
        }
        log.warn("split key finalize reconciled after lost reply taskId={} keyFingerprint={}",
                taskId, fingerprint(currentKey));
        return true;
    }

    /**
     * Unlinks the temp key and records the skip; callers own the follow-up state handling.
     * {@code estimated} is the best-known total element count — the parser's header sum on the
     * hard-cap path, the begin event's declared count on the bulk-limit path.
     */
    private void abortToList(String reason, long payloadBytes, long written, long estimated) throws IOException {
        unlinkTempBestEffort();
        emitSkip(currentKey, rdbType, payloadBytes, estimated, reason);
        log.warn("split key aborted after {} elements taskId={} keyFingerprint={} reason={}",
                written, taskId, fingerprint(currentKey), reason);
    }

    private void emitSkip(byte[] key, int type, long payloadBytes, long estimatedElements, String reason) {
        if (reason.equals("UNSUPPORTED_TYPE"))
            skippedUnsupported.increment();
        else
            skippedOversize.increment();
        progress.splitKeySkipped(payloadBytes);
        String keyName = new String(key, StandardCharsets.UTF_8);
        log.warn("split key skipped taskId={} key=\"{}\" keyBytes={} rdbType={} elements={} "
                + "payloadBytes={} reason={}", taskId, keyName, key.length, type, estimatedElements,
                payloadBytes, reason);
        try {
            sync.appendTaskEvent(taskId, "sync:split", skipEventMessage(key, type, payloadBytes,
                    estimatedElements, reason));
        } catch (RuntimeException reportingFailure) {
            // The counter and log line above already made the skip visible.
        }
    }

    /**
     * Contract 8 v2 skip event: the raw key rides last so names with spaces survive, and the
     * collection's RDB-declared element count (0 = unknown for opaque blobs) tells the operator
     * the scale. Replaced the v1 fingerprint — an operator cannot act on a hash.
     */
    static String skipEventMessage(byte[] key, int type, long payloadBytes, long estimatedElements,
            String reason) {
        return "SPLIT_KEY_SKIPPED rdbType=" + type + " keyBytes=" + key.length + " elements="
                + estimatedElements + " payloadBytes=" + payloadBytes + " reason=" + reason + " key="
                + new String(key, StandardCharsets.UTF_8);
    }

    private void resetKeyState() {
        currentKey = null;
        tempKey = null;
        rdbType = 0;
        estimatedElements = 0;
        expireAt = -1;
        elementsWritten = 0;
        listKind = false;
        aborted = false;
        batch = new ArrayList<>();
        batchBytes = 0;
    }

    private void unlinkTempBestEffort() {
        try {
            target.unlink(tempKey);
        } catch (Exception failure) {
            target.reopen(tempKey); // the connection is suspect; the crash-residue UNLINK on re-run cleans up
        }
    }

    private long bulkLimit() throws IOException {
        if (!bulkLimitProbed) {
            bulkLimitProbed = true;
            try {
                bulkLimit = Math.max(1024, target.protoMaxBulkLen());
            } catch (IOException | RuntimeException probeFailure) { // CONFIG may be renamed away
                // CONFIG may be renamed away; the hard cap stays the defensive bound.
                bulkLimit = settings.hardCapBytes();
            }
        }
        return bulkLimit;
    }

    static String fingerprint(byte[] key) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(key);
        } catch (Exception unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
        return HexFormat.of().formatHex(digest).substring(0, 16);
    }

    static byte[][] appendCommand(int rdbType, byte[] tempKey, RdbEvent.KeyElement element) {
        return switch (rdbType) {
            case T_LIST, T_LIST_ZIPLIST, T_LIST_QUICKLIST, T_LIST_QUICKLIST_2 ->
                new byte[][]{bytes("RPUSH"), tempKey, element.member()};
            case T_SET, T_SET_INTSET, T_SET_LISTPACK ->
                new byte[][]{bytes("SADD"), tempKey, element.member()};
            case T_ZSET, T_ZSET_2 ->
                new byte[][]{bytes("ZADD"), tempKey, element.score(), element.member()};
            case T_HASH, T_HASH_ZIPMAP, T_HASH_ZIPLIST, T_HASH_LISTPACK ->
                new byte[][]{bytes("HSET"), tempKey, element.member(), element.value()};
            default -> throw new SyncBlockedException("BLOCKED_UNSUPPORTED_SPLIT_TYPE",
                    "RDB type " + rdbType + " cannot be rebuilt element-by-element");
        };
    }

    private static long commandBytes(byte[][] command) {
        long total = 16; // RESP framing allowance per command
        for (byte[] argument : command)
            total += argument.length;
        return total;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
