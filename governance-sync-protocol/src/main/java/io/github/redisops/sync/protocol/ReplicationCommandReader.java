package io.github.redisops.sync.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ReplicationCommandReader {
    private final RespCodec codec;
    private final CountingInputStream input;
    private long offset;

    public ReplicationCommandReader(RespCodec codec, CountingInputStream input, long initialOffset) {
        this.codec = codec;
        this.input = input;
        this.offset = initialOffset;
    }

    public ReplicationCommand read() throws IOException {
        long before = input.count();
        int marker;
        long separators = 0;
        do {
            marker = input.read();
            if (marker < 0)
                throw new java.io.EOFException("replication stream ended");
            if (marker == '\r' || marker == '\n')
                separators++;
        } while (marker == '\r' || marker == '\n');
        RespValue value = codec.readWithMarker(marker);
        long bytes = input.count() - before - separators;
        long start = offset + 1;
        offset += bytes;
        if (!(value instanceof RespValue.Array array) || array.values().isEmpty())
            throw new RespProtocolException("replication stream item must be a non-empty RESP array");
        List<byte[]> arguments = new ArrayList<>(array.values().size());
        for (RespValue argument : array.values()) {
            if (!(argument instanceof RespValue.Bulk bulk))
                throw new RespProtocolException("replication command arguments must be bulk strings");
            arguments.add(bulk.value());
        }
        String name = new String(arguments.get(0), StandardCharsets.US_ASCII);
        return new ReplicationCommand(name, arguments, start, offset);
    }

    public long offset() {
        return offset;
    }
}
