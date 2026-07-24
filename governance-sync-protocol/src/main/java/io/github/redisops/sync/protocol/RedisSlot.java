package io.github.redisops.sync.protocol;

import java.nio.charset.StandardCharsets;

public final class RedisSlot {
    private RedisSlot() {
    }
    public static int of(String key) {
        return of(key.getBytes(StandardCharsets.UTF_8));
    }
    public static int of(byte[] key) {
        int start = -1, end = -1;
        for (int i = 0; i < key.length; i++)
            if (key[i] == '{') {
                start = i + 1;
                break;
            }
        if (start >= 0)
            for (int i = start; i < key.length; i++)
                if (key[i] == '}') {
                    end = i;
                    break;
                }
        int offset = start >= 0 && end > start ? start : 0,
                length = start >= 0 && end > start ? end - start : key.length;
        return crc16(key, offset, length) & 16383;
    }
    private static int crc16(byte[] bytes, int offset, int length) {
        int crc = 0;
        for (int i = offset; i < offset + length; i++) {
            crc ^= (bytes[i] & 0xff) << 8;
            for (int bit = 0; bit < 8; bit++)
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
        }
        return crc & 0xffff;
    }
}
