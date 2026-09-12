package io.github.redisops.sync.protocol;

import java.util.List;

public final class KeyFilter {
    private final List<BinaryGlob> includes, excludes;
    public KeyFilter(List<String> includes, List<String> excludes) {
        this.includes = (includes == null || includes.isEmpty() ? List.of("*") : includes).stream().map(BinaryGlob::new)
                .toList();
        this.excludes = (excludes == null ? List.<String>of() : excludes).stream().map(BinaryGlob::new).toList();
    }
    public boolean accepts(byte[] key) {
        return includes.stream().anyMatch(x -> x.matches(key)) && excludes.stream().noneMatch(x -> x.matches(key));
    }
}
