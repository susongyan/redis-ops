package io.github.redisops.sync.protocol;

import java.nio.charset.StandardCharsets;

public final class BinaryGlob {
    private final byte[] pattern;
    public BinaryGlob(String pattern) {
        this.pattern = pattern.getBytes(StandardCharsets.UTF_8);
    }
    public boolean matches(byte[] value) {
        return match(0, 0, value);
    }
    private boolean match(int p, int v, byte[] value) {
        while (p < pattern.length) {
            byte token = pattern[p++];
            if (token == '*') {
                while (p < pattern.length && pattern[p] == '*')
                    p++;
                if (p == pattern.length)
                    return true;
                for (int i = v; i <= value.length; i++)
                    if (match(p, i, value))
                        return true;
                return false;
            }
            if (v >= value.length)
                return false;
            if (token == '?') {
                v++;
                continue;
            }
            if (token == '\\' && p < pattern.length)
                token = pattern[p++];
            if (token != value[v++])
                return false;
        }
        return v == value.length;
    }
}
