package io.github.susongyan.redisops.worker.protocol;

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
    /** RESP wire size without creating argument copies or an encoded buffer. */
    public long encodedBytes() {
        long size = 3L + Integer.toString(arguments.size()).length();
        for (byte[] argument : arguments)
            size += 5L + Integer.toString(argument.length).length() + argument.length;
        return size;
    }
    public int argumentCount() {
        return arguments.size();
    }
}
