package io.github.susongyan.redisops.platform.infrastructure.redis;

import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.NestedMultiOutput;
import java.nio.ByteBuffer;

/** Limits allocations before NestedMultiOutput allocates an array or copies a bulk value. */
final class ConsoleCommandOutput extends NestedMultiOutput<byte[], byte[]> {
    private int elements, bytes, depth, reserved;
    private boolean array;
    ConsoleCommandOutput() {
        super(ByteArrayCodec.INSTANCE);
    }
    private void element() {
        if (++elements > 2048)
            throw new IllegalArgumentException("RESPONSE_ELEMENT_LIMIT");
    }
    private void bulk(ByteBuffer value) {
        element();
        if (value != null)
            bytes += value.remaining();
        if (bytes > 65536)
            throw new IllegalArgumentException("RESPONSE_BYTE_LIMIT");
    }
    @Override
    public void multi(int count) {
        if (count > 2048 || (reserved += Math.max(0, count)) > 2048 || ++depth > 16)
            throw new IllegalArgumentException("RESPONSE_STRUCTURE_LIMIT");
        element();
        array = true;
        super.multi(count);
    }
    @Override
    public void complete(int level) {
        super.complete(level);
        depth = Math.min(depth, level);
    }
    @Override
    public void set(ByteBuffer value) {
        bulk(value);
        super.set(value);
    }
    @Override
    public void setSingle(ByteBuffer value) {
        bulk(value);
        super.setSingle(value);
    }
    @Override
    public void set(long value) {
        element();
        super.set(value);
    }
    @Override
    public void set(double value) {
        element();
        super.set(value);
    }
    Object result() {
        return array ? get() : get() == null || get().isEmpty() ? null : get().get(0);
    }
}
