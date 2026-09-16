package io.github.susongyan.redisops.worker.protocol;

import java.util.ArrayList;
import java.util.List;

/** Keeps transaction boundaries across spool/live batches and suppresses their overlapping delivery. */
public final class SourceExecutionBatcher {
    private final SourceTransactionAssembler transactions = new SourceTransactionAssembler();
    private long consumedOffset = -1;

    public List<SourceTransactionAssembler.Unit> accept(List<ReplicationCommand> commands, long committedOffset) {
        List<SourceTransactionAssembler.Unit> result = new ArrayList<>();
        List<ReplicationCommand> ordinary = new ArrayList<>();
        for (var command : commands) {
            if (command.endOffset() <= Math.max(consumedOffset, committedOffset))
                continue;
            var completed = transactions.accept(command);
            consumedOffset = command.endOffset();
            if (completed.isEmpty()) {
                flush(ordinary, result);
                continue;
            }
            var unit = completed.get();
            if (unit.transaction()) {
                flush(ordinary, result);
                result.add(unit);
            } else {
                ordinary.add(command);
                if (ordinary.size() == 100)
                    flush(ordinary, result);
            }
        }
        flush(ordinary, result);
        return result;
    }

    public boolean hasOpenTransaction() {
        return transactions.hasOpenTransaction();
    }
    public void endOfInput() {
        transactions.endOfInput();
    }

    private void flush(List<ReplicationCommand> commands, List<SourceTransactionAssembler.Unit> result) {
        if (commands.isEmpty())
            return;
        result.add(new SourceTransactionAssembler.Unit(false, commands, commands.get(0).startOffset(),
                commands.get(commands.size() - 1).endOffset()));
        commands.clear();
    }
}
