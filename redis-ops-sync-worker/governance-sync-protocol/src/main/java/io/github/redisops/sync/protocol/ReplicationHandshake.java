package io.github.redisops.sync.protocol;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class ReplicationHandshake {
    private final RespCodec codec;
    public ReplicationHandshake(RespCodec codec) {
        this.codec = codec;
    }

    public ReplicationReply start(String username, char[] password, String replicationId, long offset)
            throws IOException {
        if (password != null) {
            byte[] encoded = encode(password);
            try {
                if (username == null || username.isBlank())
                    commandExpectOk(new byte[][]{ascii("AUTH"), encoded});
                else
                    commandExpectOk(new byte[][]{ascii("AUTH"), username.getBytes(StandardCharsets.UTF_8), encoded});
            } finally {
                Arrays.fill(encoded, (byte) 0);
            }
        }
        commandExpect("PING", "PONG");
        commandExpectOk("REPLCONF", "listening-port", "0");
        commandExpectOk("REPLCONF", "capa", "eof", "capa", "psync2");
        codec.writeCommand("PSYNC", replicationId == null ? "?" : replicationId, Long.toString(offset));
        String response = simple(readPsyncReply());
        if (response.startsWith("FULLRESYNC ")) {
            String[] parts = response.split(" ");
            if (parts.length != 3)
                throw new RespProtocolException("invalid FULLRESYNC response");
            return new ReplicationReply.FullResync(parts[1], Long.parseLong(parts[2]), readRdbHeader());
        }
        if (response.startsWith("CONTINUE")) {
            String[] parts = response.split(" ");
            return new ReplicationReply.Continue(parts.length > 1 ? parts[1] : replicationId);
        }
        throw new RespProtocolException("unexpected PSYNC response: " + response);
    }

    public void acknowledge(long offset) throws IOException {
        codec.writeCommand("REPLCONF", "ACK", Long.toString(offset));
    }

    private ReplicationReply.RdbTransfer readRdbHeader() throws IOException {
        InputStream input = codec.input();
        int marker = input.read();
        if (marker != '$')
            throw new RespProtocolException("RDB transfer does not start with bulk marker");
        String header = line(input);
        if (header.startsWith("EOF:")) {
            String markerValue = header.substring(4);
            if (markerValue.length() != 40)
                throw new RespProtocolException("invalid diskless EOF marker");
            return new ReplicationReply.RdbTransfer(-1, markerValue);
        }
        long length;
        try {
            length = Long.parseLong(header);
        } catch (NumberFormatException e) {
            throw new RespProtocolException("invalid RDB length", e);
        }
        if (length < 0)
            throw new RespProtocolException("invalid RDB length: " + length);
        return new ReplicationReply.RdbTransfer(length, null);
    }

    private void commandExpectOk(String... command) throws IOException {
        commandExpect(command, "OK");
    }

    private RespValue readPsyncReply() throws IOException {
        InputStream input = codec.input();
        if (!(input instanceof PushbackInputStream pushback))
            return codec.read();
        int marker;
        do {
            marker = pushback.read();
            if (marker < 0)
                throw new EOFException("PSYNC response ended");
        } while (marker == '\r' || marker == '\n');
        pushback.unread(marker);
        return codec.read();
    }
    private void commandExpectOk(byte[][] command) throws IOException {
        codec.writeCommand(command);
        String value = simple(codec.read());
        if (!"OK".equalsIgnoreCase(value))
            throw new RespProtocolException("AUTH failed: " + value);
    }
    private void commandExpect(String first, String expected) throws IOException {
        commandExpect(new String[]{first}, expected);
    }
    private void commandExpect(String[] command, String expected) throws IOException {
        codec.writeCommand(command);
        String value = simple(codec.read());
        if (!expected.equalsIgnoreCase(value))
            throw new RespProtocolException(command[0] + " failed: " + value);
    }
    private static String simple(RespValue value) {
        if (value instanceof RespValue.Simple s)
            return s.value();
        if (value instanceof RespValue.Error e)
            throw new RespProtocolException("Redis error: " + e.value());
        throw new RespProtocolException("expected simple Redis response");
    }
    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int current = input.read();
            if (current < 0)
                throw new EOFException("RDB header ended");
            if (previous == '\r' && current == '\n') {
                byte[] bytes = out.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.US_ASCII);
            }
            out.write(current);
            previous = current;
        }
    }

    private static byte[] encode(char[] value) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value));
            byte[] result = new byte[encoded.remaining()];
            encoded.get(result);
            if (encoded.hasArray())
                Arrays.fill(encoded.array(), (byte) 0);
            return result;
        } catch (Exception error) {
            throw new RespProtocolException("cannot encode Redis password", error);
        }
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
