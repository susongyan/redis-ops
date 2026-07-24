package io.github.redisops.sync.protocol;

public final class RedisCrc64 {
    private static final long POLYNOMIAL = 0x95AC9329AC4BC9B5L;
    private static final long[] TABLE = new long[256];
    static {
        for (int i = 0; i < 256; i++) {
            long crc = i;
            for (int bit = 0; bit < 8; bit++)
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ POLYNOMIAL : crc >>> 1;
            TABLE[i] = crc;
        }
    }
    private RedisCrc64() {
    }
    public static long update(long crc, byte[] value, int offset, int length) {
        for (int i = offset; i < offset + length; i++)
            crc = TABLE[((int) crc ^ value[i]) & 0xff] ^ (crc >>> 8);
        return crc;
    }
    public static long calculate(byte[] value) {
        return update(0, value, 0, value.length);
    }
}
