package io.github.susongyan.redisops.worker.runtime;

import io.github.susongyan.redisops.worker.protocol.*;
import io.github.susongyan.redisops.sync.contract.SyncCommandCapabilities;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** Fixed process-wide observations, not persisted progress or a recovery fact. No task/key labels. */
public final class SyncCommandObservations {
    private static final LongAdder APPLIED = new LongAdder(), FILTERED = new LongAdder(), EXPANDED = new LongAdder(),
            OPERATIONS = new LongAdder(), TRANSACTIONS = new LongAdder(), SKIPPED_TRANSACTIONS = new LongAdder(),
            BLOCKED = new LongAdder();
    private SyncCommandObservations() {
    }
    public static Map<String, Long> snapshot() {
        return Map.of("appliedSourceCommands", APPLIED.sum(), "filteredSourceCommands", FILTERED.sum(),
                "expandedOrdinarySourceCommands", EXPANDED.sum(), "targetOperations", OPERATIONS.sum(),
                "appliedTransactions", TRANSACTIONS.sum(), "skippedTransactions", SKIPPED_TRANSACTIONS.sum(),
                "blockedExecutions", BLOCKED.sum());
    }
    public static void blocked() {
        BLOCKED.increment();
    }
    public static final class Batch {
        private long applied, filtered, expanded, operations, transactions, skippedTransactions;
        public void ordinary(ReplicationCommand command, CommandPlan plan) {
            if (SyncCommandCapabilities.skipped(command.name()))
                return;
            if (plan.disposition() == CommandPlan.Disposition.APPLY) {
                applied++;
                operations += plan.commands().size();
                if (plan.commands().size() > 1)
                    expanded++;
            } else if (plan.disposition() == CommandPlan.Disposition.SKIP)
                filtered++;
        }
        public void transaction(SourceTransactionAssembler.Unit unit, CommandPlan plan) {
            long count = unit.commands().stream().filter(command -> !command.name().equals("SELECT")).count();
            if (plan.disposition() == CommandPlan.Disposition.APPLY) {
                applied += count;
                operations += plan.commands().size();
                transactions++;
            } else if (plan.disposition() == CommandPlan.Disposition.SKIP) {
                filtered += count;
                skippedTransactions++;
            }
        }
        public void confirmed() {
            APPLIED.add(applied);
            FILTERED.add(filtered);
            EXPANDED.add(expanded);
            OPERATIONS.add(operations);
            TRANSACTIONS.add(transactions);
            SKIPPED_TRANSACTIONS.add(skippedTransactions);
        }
    }
}
