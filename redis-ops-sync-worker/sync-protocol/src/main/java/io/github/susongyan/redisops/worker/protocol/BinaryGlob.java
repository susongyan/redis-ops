package io.github.susongyan.redisops.worker.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public final class BinaryGlob {
    private final int[] tokens;
    public BinaryGlob(String pattern) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        var parsed = new ArrayList<Integer>();
        for (int i = 0; i < bytes.length; i++) {
            int value = Byte.toUnsignedInt(bytes[i]);
            if (value == '\\' && i + 1 < bytes.length)
                parsed.add(Byte.toUnsignedInt(bytes[++i]));
            else
                parsed.add(value == '*' ? -1 : value == '?' ? -2 : value);
        }
        tokens = parsed.stream().mapToInt(Integer::intValue).toArray();
    }
    public boolean matches(byte[] value) {
        int p = 0, v = 0, star = -1, retry = 0;
        while (v < value.length) {
            if (p < tokens.length && (tokens[p] == -2 || tokens[p] == Byte.toUnsignedInt(value[v]))) {
                p++;
                v++;
            } else if (p < tokens.length && tokens[p] == -1) {
                star = p++;
                retry = v;
            } else if (star >= 0) {
                p = star + 1;
                v = ++retry;
            } else
                return false;
        }
        while (p < tokens.length && tokens[p] == -1)
            p++;
        return p == tokens.length;
    }
}
