package io.github.redisops.sync.protocol;

import java.util.List;

public record ReplicationCommand(String name, List<byte[]> arguments, long startOffset, long endOffset) {
    public ReplicationCommand {
        name = name.toUpperCase(java.util.Locale.ROOT);
        arguments = arguments.stream().map(byte[]::clone).toList();
    }
    @Override
    public List<byte[]> arguments() {
        return arguments.stream().map(byte[]::clone).toList();
    }
}
