package io.github.susongyan.redisops.worker.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** One replication channel, one consumer. Never emits an unfinished source transaction. */
public final class SourceTransactionAssembler {
    public static final int MAX_COMMANDS = 10_000;
    public static final long MAX_BYTES = 16L * 1024 * 1024;
    private final int commandLimit;
    private final long byteLimit;
    private final List<ReplicationCommand> pending = new ArrayList<>();
    private boolean open;
    private boolean failed;
    private long bytes;
    private long startOffset;
    private long lastOffset = -1;

    public record Unit(boolean transaction, List<ReplicationCommand> commands, long startOffset, long endOffset) {
        public Unit {
            commands = List.copyOf(commands);
        }
    }

    public SourceTransactionAssembler() {
        this(MAX_COMMANDS, MAX_BYTES);
    }

    public SourceTransactionAssembler(int commandLimit, long byteLimit) {
        if (commandLimit < 1 || commandLimit > MAX_COMMANDS || byteLimit < 1 || byteLimit > MAX_BYTES)
            throw new IllegalArgumentException("INVALID_TRANSACTION_LIMIT");
        this.commandLimit = commandLimit;
        this.byteLimit = byteLimit;
    }

    public Optional<Unit> accept(ReplicationCommand command) {
        if (failed)
            throw new IllegalStateException("BLOCKED_TRANSACTION_ASSEMBLER_FAILED");
        if (command.endOffset() < command.startOffset() || command.endOffset() <= lastOffset)
            return fail("BLOCKED_TRANSACTION_OFFSET_ORDER");
        lastOffset = command.endOffset();
        String name = command.name();
        if (name.equals("MULTI")) {
            if (open || command.argumentCount() != 1)
                return fail("BLOCKED_TRANSACTION_STRUCTURE");
            open = true;
            startOffset = command.startOffset();
            bytes = command.encodedBytes();
            if (bytes > byteLimit)
                return fail("BLOCKED_TRANSACTION_LIMIT");
            return Optional.empty();
        }
        if (name.equals("EXEC")) {
            if (!open || command.argumentCount() != 1)
                return fail("BLOCKED_TRANSACTION_STRUCTURE");
            if (command.encodedBytes() > byteLimit - bytes)
                return fail("BLOCKED_TRANSACTION_LIMIT");
            Unit unit = new Unit(true, pending, startOffset, command.endOffset());
            pending.clear();
            bytes = 0;
            open = false;
            return Optional.of(unit);
        }
        // DISCARD is not a committed replication effect. Reject unexpected control frames.
        if (name.equals("DISCARD") || name.equals("WATCH") || name.equals("UNWATCH"))
            return fail("BLOCKED_TRANSACTION_STRUCTURE");
        if (!open)
            return Optional.of(new Unit(false, List.of(command), command.startOffset(), command.endOffset()));
        long size = command.encodedBytes();
        if (pending.size() >= commandLimit || size > byteLimit - bytes)
            return fail("BLOCKED_TRANSACTION_LIMIT");
        pending.add(command);
        bytes += size;
        return Optional.empty();
    }

    public boolean hasOpenTransaction() {
        return open;
    }
    public int bufferedCommands() {
        return pending.size();
    }
    public long bufferedBytes() {
        return bytes;
    }

    /** Terminal end, not a transient socket timeout or a resumable disconnect. */
    public void endOfInput() {
        if (open)
            fail("BLOCKED_TRANSACTION_INCOMPLETE");
    }

    private Optional<Unit> fail(String reason) {
        pending.clear();
        bytes = 0;
        failed = true;
        throw new IllegalStateException(reason);
    }
}
