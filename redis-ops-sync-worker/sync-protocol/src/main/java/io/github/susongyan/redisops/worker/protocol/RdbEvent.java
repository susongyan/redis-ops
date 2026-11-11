package io.github.susongyan.redisops.worker.protocol;

/**
 * Parser callbacks for one RDB stream. The parser allocates a fresh array per string it reads
 * ({@code RdbStreamParser.readString} never reuses buffers), so events carry the one and only copy
 * and accessors return it directly — a full-payload defensive copy per access would multiply the
 * heap cost of every large key. Callers therefore must not mutate the arrays behind an event.
 *
 * <p>
 * The {@code BigKeyValue*}/{@code KeyElement}/{@code SkippedKey} variants are only produced by
 * {@code RdbStreamParser.parse(consumer, SplitOptions)} in split mode; the single-argument
 * {@code parse} keeps emitting only {@code SelectDb}/{@code KeyValue}/{@code FunctionLibrary}/{@code End}.
 */
public sealed interface RdbEvent permits RdbEvent.SelectDb, RdbEvent.KeyValue, RdbEvent.FunctionLibrary, RdbEvent.End,
        RdbEvent.BigKeyValueBegin, RdbEvent.KeyElement, RdbEvent.BigKeyValueEnd, RdbEvent.SkippedKey {
    record SelectDb(int database) implements RdbEvent {
    }
    record KeyValue(int database, byte[] key, long absoluteExpireMillis, byte[] dumpPayload) implements RdbEvent {
    }
    record FunctionLibrary(byte[] payload) implements RdbEvent {
    }
    enum End implements RdbEvent {
        INSTANCE
    }

    /**
     * Opens element-level streaming for one key whose value exceeds the RESTORE threshold.
     * {@code estimatedElements} is the RDB-declared count (collection length / quicklist node count),
     * used for progress display only.
     */
    record BigKeyValueBegin(int database, byte[] key, long absoluteExpireMillis, int rdbType,
            long estimatedElements) implements RdbEvent {
    }

    /**
     * One element of a big key, filled by RDB type semantics: list/set member only; hash field in
     * {@code member} with value in {@code value}; zset member in {@code member} with the score as
     * text in {@code score} (legacy scores pass through verbatim, binary scores are rendered by
     * {@code Double.toString} with {@code +inf}/{@code -inf}/{@code nan} specials — ZADD/strtod
     * round-trips all of them). Unused slots are {@code null}.
     */
    record KeyElement(byte[] member, byte[] value, byte[] score) implements RdbEvent {
    }

    /** Closes element-level streaming; {@code payloadBytes} is the exact wire size of the value. */
    record BigKeyValueEnd(byte[] key, long payloadBytes, long elementCount) implements RdbEvent {
    }

    /**
     * A key the sync cannot carry with any strategy. {@code OVER_HARD_CAP} values were streamed or
     * discarded without a usable payload; {@code UNSUPPORTED_TYPE} covers opt-in module skipping.
     * May arrive between {@code BigKeyValueBegin} and its end when a mid-stream cap check trips —
     * the writer must then abort the open temp key instead of finalizing it.
     * {@code estimatedElements} is the RDB-declared collection length when the skip fired on the
     * element path (list/set/zset/hash), and for quicklists the exact sum of the per-node header
     * counts (ziplist {@code zllen} / listpack header; plain node = 1) taken while consuming the
     * value — the wire has no whole-list total, and LZF-compressed nodes are prefix-inflated
     * just far enough to read their header. {@code 0} = unknowable: pre-encoding blobs are
     * opaque, and a node whose header prefix will not inflate, that declares >65534 entries
     * (0xFFFF), or that is integer-encoded would need a full decode, which the hard cap exists
     * to avoid.
     */
    record SkippedKey(byte[] key, int rdbType, long payloadBytes, SkipReason reason, long estimatedElements)
            implements
                RdbEvent {
    }

    enum SkipReason {
        OVER_HARD_CAP, UNSUPPORTED_TYPE
    }
}
