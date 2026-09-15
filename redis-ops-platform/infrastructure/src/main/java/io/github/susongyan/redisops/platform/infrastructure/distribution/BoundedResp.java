package io.github.susongyan.redisops.platform.infrastructure.distribution;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import io.github.susongyan.redisops.platform.domain.distribution.DistributionScanPort;

/** Strict, allocation-bounded RESP2 receiver. No server text is included in failures. */
public final class BoundedResp {
    public static final int MAX_RESPONSE = 1024 * 1024, MAX_KEY = 4096, MAX_ELEMENTS = 5000;
    private final InputStream input;
    private final Runnable checkDeadline;
    private final DistributionReceiveLimits limits;
    private int consumed, elements;
    public BoundedResp(InputStream input, Runnable checkDeadline) {
        this(input, checkDeadline, DistributionReceiveLimits.defaults());
    }
    public BoundedResp(InputStream input, Runnable checkDeadline, DistributionReceiveLimits limits) {
        this.input = input;
        this.checkDeadline = checkDeadline;
        this.limits = limits;
    }
    private int read() throws IOException {
        checkDeadline.run();
        if (++consumed > limits.responseBytes())
            throw new IOException("SCAN_RESPONSE_LIMIT");
        int b = input.read();
        if (b < 0)
            throw new EOFException("SCAN_RESPONSE_TRUNCATED");
        return b;
    }
    private String line() throws IOException {
        byte[] buffer = new byte[128];
        int length = 0;
        for (;;) {
            int b = read();
            if (b == '\r') {
                if (read() != '\n')
                    throw new IOException("SCAN_INVALID_RESP");
                break;
            }
            if (length == buffer.length || b < 32 || b > 126)
                throw new IOException("SCAN_INVALID_RESP");
            buffer[length++] = (byte) b;
        }
        return new String(buffer, 0, length, StandardCharsets.US_ASCII);
    }
    private long number() throws IOException {
        String text = line();
        if (!text.matches("-?[0-9]{1,18}"))
            throw new IOException("SCAN_INVALID_RESP");
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IOException("SCAN_INVALID_RESP");
        }
    }
    private void marker(int expected) throws IOException {
        int b = read();
        if (b == '-')
            throw new IOException("SCAN_REDIS_ERROR");
        if (b != expected)
            throw new IOException("SCAN_INVALID_RESP");
    }
    private byte[] bulk(int allocationLimit, boolean discard) throws IOException {
        long length = number();
        if (length < 0 || length > limits.responseBytes() - consumed - 2)
            throw new IOException("SCAN_RESPONSE_LIMIT");
        if (length > allocationLimit && !discard)
            throw new IOException("SCAN_RESPONSE_LIMIT");
        byte[] result = length <= allocationLimit ? new byte[(int) length] : null;
        for (int i = 0; i < length; i++) {
            int b = read();
            if (result != null)
                result[i] = (byte) b;
        }
        if (read() != '\r' || read() != '\n')
            throw new IOException("SCAN_INVALID_RESP");
        return result;
    }
    public DistributionScanPort.Page scan() throws IOException {
        marker('*');
        if (number() != 2)
            throw new IOException("SCAN_INVALID_RESP");
        marker('$');
        byte[] rawCursor = bulk(32, false);
        String cursor = new String(rawCursor, StandardCharsets.US_ASCII);
        if (!cursor.matches("[0-9]{1,20}"))
            throw new IOException("SCAN_INVALID_CURSOR");
        marker('*');
        long count = number();
        if (count < 0 || count > limits.elements())
            throw new IOException("SCAN_ELEMENT_LIMIT");
        List<byte[]> keys = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            marker('$');
            keys.add(bulk(limits.keyBytes(), true));
        }
        return new DistributionScanPort.Page(cursor, Collections.unmodifiableList(keys));
    }
    public Object metadata() throws IOException {
        return metadata(0);
    }
    private Object metadata(int depth) throws IOException {
        if (depth > 4 || ++elements > limits.elements())
            throw new IOException("SCAN_METADATA_LIMIT");
        int marker = read();
        return switch (marker) {
            case '+' -> line();
            case ':' -> number();
            case '$' -> bulk(256 * 1024, false);
            case '*' -> {
                long size = number();
                if (size < 0 || size > 1024)
                    throw new IOException("SCAN_METADATA_LIMIT");
                List<Object> values = new ArrayList<>();
                for (int i = 0; i < size; i++)
                    values.add(metadata(depth + 1));
                yield values;
            }
            default -> throw new IOException("SCAN_REDIS_ERROR");
        };
    }
}
