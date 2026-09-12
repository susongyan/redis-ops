package io.github.redisops.sync.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class RespCodec {
    private static final byte[] CRLF = {'\r', '\n'};
    private final InputStream input;
    private final OutputStream output;

    public RespCodec(InputStream input, OutputStream output) {
        this.input = input;
        this.output = output;
    }

    public RespValue read() throws IOException {
        int marker = input.read();
        if (marker < 0)
            throw new EOFException("RESP stream ended");
        return readWithMarker(marker);
    }

    RespValue readWithMarker(int marker) throws IOException {
        return switch (marker) {
            case '+' -> new RespValue.Simple(line());
            case '-' -> new RespValue.Error(line());
            case ':' -> new RespValue.IntegerValue(parseLong(line(), "integer"));
            case '$' -> bulk();
            case '*' -> array();
            default -> throw new RespProtocolException("unsupported RESP marker: " + marker);
        };
    }

    public void writeCommand(byte[]... arguments) throws IOException {
        writeCommandBuffered(arguments);
        output.flush();
    }

    public void writeCommandBuffered(byte[]... arguments) throws IOException {
        output.write(('*' + Integer.toString(arguments.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (byte[] argument : arguments) {
            output.write(('$' + Integer.toString(argument.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(argument);
            output.write(CRLF);
        }
    }

    public void flush() throws IOException {
        output.flush();
    }

    public void writeCommand(String... arguments) throws IOException {
        byte[][] bytes = new byte[arguments.length][];
        for (int i = 0; i < arguments.length; i++)
            bytes[i] = arguments[i].getBytes(StandardCharsets.UTF_8);
        writeCommand(bytes);
    }

    public InputStream input() {
        return input;
    }

    private RespValue bulk() throws IOException {
        long length = parseLong(line(), "bulk length");
        if (length == -1)
            return RespValue.NullValue.INSTANCE;
        if (length < 0 || length > Integer.MAX_VALUE)
            throw new RespProtocolException("invalid bulk length: " + length);
        byte[] value = readExactly((int) length);
        expectCrlf();
        return new RespValue.Bulk(value);
    }

    private RespValue array() throws IOException {
        long length = parseLong(line(), "array length");
        if (length == -1)
            return RespValue.NullValue.INSTANCE;
        if (length < 0 || length > 1_000_000)
            throw new RespProtocolException("invalid array length: " + length);
        List<RespValue> values = new ArrayList<>((int) length);
        for (int i = 0; i < length; i++)
            values.add(read());
        return new RespValue.Array(values);
    }

    private String line() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int current = input.read();
            if (current < 0)
                throw new EOFException("RESP line ended");
            if (previous == '\r' && current == '\n') {
                byte[] bytes = buffer.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
            }
            buffer.write(current);
            previous = current;
            if (buffer.size() > 1024 * 1024)
                throw new RespProtocolException("RESP line exceeds 1 MiB");
        }
    }

    private byte[] readExactly(int length) throws IOException {
        byte[] result = input.readNBytes(length);
        if (result.length != length)
            throw new EOFException("expected " + length + " bytes, received " + result.length);
        return result;
    }
    private void expectCrlf() throws IOException {
        if (input.read() != '\r' || input.read() != '\n')
            throw new RespProtocolException("bulk value is not terminated by CRLF");
    }
    private static long parseLong(String value, String field) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new RespProtocolException("invalid " + field + ": " + value, e);
        }
    }
}
