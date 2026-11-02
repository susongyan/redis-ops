package io.github.susongyan.redisops.platform.domain.history;

import java.util.Set;

/** Platform read-only history lifecycle; never an execution or Redis port. */
public interface ExecutionRecordRepository {
    enum Type {
        RISK_SCAN(Set.of("COMPLETED", "FAILED", "CANCELLED")), KEY_DISTRIBUTION(
                Set.of("COMPLETED", "INCOMPLETE", "FAILED", "CANCELLED")), VALIDATION(
                        Set.of("PASSED", "FAILED", "INCONCLUSIVE", "CANCELLED")), ANALYSIS(Set.of("COMPLETED"));
        private final Set<String> terminal;
        Type(Set<String> terminal) {
            this.terminal = terminal;
        }
        public boolean canDelete(String status) {
            return terminal.contains(status);
        }
    }
    record Snapshot(long version, String status, boolean linked, boolean executing) {
    }
    Snapshot lock(Type type, long id);
    boolean delete(Type type, long id, long version);

    /**
     * Hard-deletes the detail rows (runs, findings, differences, checkpoints, groups) owned by the
     * given task; runs in the caller's transaction so a rejected delete rolls the purge back.
     * Returns the number of purged rows.
     */
    int purgeDetails(Type type, long id);
}
