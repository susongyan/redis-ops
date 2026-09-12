package io.github.redisops.sync.protocol;

import java.util.List;

public record CommandPlan(Disposition disposition, List<PlannedCommand> commands, String reason) {
    public enum Disposition {
        APPLY, SKIP, BLOCK
    }
    public record PlannedCommand(int slot, List<byte[]> arguments) {
        public PlannedCommand {
            arguments = arguments.stream().map(byte[]::clone).toList();
        }
        @Override
        public List<byte[]> arguments() {
            return arguments.stream().map(byte[]::clone).toList();
        }
    }
    public static CommandPlan skip() {
        return new CommandPlan(Disposition.SKIP, List.of(), null);
    }
    public static CommandPlan block(String reason) {
        return new CommandPlan(Disposition.BLOCK, List.of(), reason);
    }
}
