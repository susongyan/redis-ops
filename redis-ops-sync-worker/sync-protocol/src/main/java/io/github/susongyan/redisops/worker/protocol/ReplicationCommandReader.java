package io.github.susongyan.redisops.worker.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ReplicationCommandReader {
    private final RespCodec codec;
    private final CountingInputStream input;
    private long offset;
    private final int maxFrameBytes;
    private final int maxArguments;

    public ReplicationCommandReader(RespCodec codec, CountingInputStream input, long initialOffset) {
        this(codec, input, initialOffset, 0, 0);
    }

    public ReplicationCommandReader(RespCodec codec, CountingInputStream input, long initialOffset,
            int maxFrameBytes, int maxArguments) {
        if (maxFrameBytes < 0 || maxFrameBytes > SourceTransactionAssembler.MAX_BYTES
                || maxFrameBytes > 0 && (maxArguments < 1 || maxArguments > 65_536))
            throw new IllegalArgumentException("INVALID_REPLICATION_READ_LIMIT");
        this.codec = codec;
        this.input = input;
        this.offset = initialOffset;
        this.maxFrameBytes = maxFrameBytes;
        this.maxArguments = maxArguments;
    }

    public ReplicationCommand read() throws IOException {
        return read(maxFrameBytes, maxArguments);
    }

    public ReplicationCommand readBounded() throws IOException {
        return read((int) SourceTransactionAssembler.MAX_BYTES, 65_536);
    }

    private ReplicationCommand read(int maxFrameBytes, int maxArguments) throws IOException {
        long before = input.count();
        try {
            return readFrame(maxFrameBytes, maxArguments);
        } catch (java.net.SocketTimeoutException timeout) {
            if (input.count() != before)
                throw new java.io.EOFException("incomplete replication frame; reconnect from last complete offset");
            throw timeout;
        }
    }

    private ReplicationCommand readFrame(int maxFrameBytes, int maxArguments) throws IOException {
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
        RespValue value = maxFrameBytes == 0
                ? codec.readWithMarker(marker)
                : codec.readCommandWithMarker(marker, maxFrameBytes, maxArguments);
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
