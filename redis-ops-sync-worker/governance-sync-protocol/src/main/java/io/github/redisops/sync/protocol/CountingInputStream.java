package io.github.redisops.sync.protocol;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

public final class CountingInputStream extends FilterInputStream {
    private long count;

    public CountingInputStream(InputStream input) {
        super(input);
    }

    public long count() {
        return count;
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value >= 0)
            count++;
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int read = super.read(buffer, offset, length);
        if (read > 0)
            count += read;
        return read;
    }
}
