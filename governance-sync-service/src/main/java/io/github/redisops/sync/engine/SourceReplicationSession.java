package io.github.redisops.sync.engine;

import io.github.redisops.domain.asset.RedisConnectionProfile;
import io.github.redisops.sync.protocol.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

public final class SourceReplicationSession implements AutoCloseable {
    private final Socket socket;
    private final PushbackInputStream input;
    private final OutputStream output;
    private final ReplicationHandshake handshake;
    private ReplicationCommandReader commands;

    public SourceReplicationSession(RedisConnectionProfile profile, Duration connectTimeout) throws IOException {
        this(profile, RedisEndpoint.parse(profile.seedEndpoints().get(0)), connectTimeout);
    }

    public SourceReplicationSession(RedisConnectionProfile profile, RedisEndpoint endpoint,
            Duration connectTimeout) throws IOException {
        socket = new Socket();
        socket.setKeepAlive(true);
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()),
                Math.toIntExact(connectTimeout.toMillis()));
        input = new PushbackInputStream(new BufferedInputStream(socket.getInputStream(), 64 * 1024), 64 * 1024);
        output = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
        handshake = new ReplicationHandshake(new RespCodec(input, output));
    }

    public ReplicationReply start(RedisConnectionProfile profile, String replicationId, long offset)
            throws IOException {
        return handshake.start(profile.username(), profile.password(), replicationId, offset);
    }

    public long spoolRdb(ReplicationReply.FullResync reply, EncryptedSpool spool) throws IOException {
        long bytes = spool.writeRdb(input, reply.transfer().length(), reply.transfer().eofMarker());
        CountingInputStream counting = new CountingInputStream(input);
        commands = new ReplicationCommandReader(new RespCodec(counting, output), counting, reply.offset());
        return bytes;
    }

    public void continueCommands(long initialOffset) {
        CountingInputStream counting = new CountingInputStream(input);
        commands = new ReplicationCommandReader(new RespCodec(counting, output), counting, initialOffset);
    }

    public ReplicationCommand readCommand() throws IOException {
        if (commands == null)
            throw new IllegalStateException("replication command stream has not started");
        return commands.read();
    }

    public void acknowledge(long offset) throws IOException {
        handshake.acknowledge(offset);
    }

    public void readTimeout(Duration timeout) throws IOException {
        socket.setSoTimeout(Math.toIntExact(timeout.toMillis()));
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Best effort during shutdown.
        }
    }
}
