package io.github.susongyan.redisops.platform.domain.distribution;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DistributionCoreTest {
    private DistributionRule rule(String delimiter, int n) {
        return new DistributionRule("r", "业务", "", DistributionRule.Kind.SEGMENTS, delimiter, n);
    }
    @Test
    void handlesLiteralSeparatorsEmptySegmentsAndTooFewParts() {
        String[][] vectors = {{"order:detail:123", ":", "2", "order:detail"},
                {"user||profile||42", "||", "2", "user||profile"}, {"a::b", ":", "2", "a:"}, {"a.b.c", ".", "1", "a"},
                {"foo", ":", "2", "STRUCTURE_MISMATCH"}, {":x", ":", "1", ""}};
        for (var v : vectors)
            assertEquals(v[3], new DistributionClassifier(List.of(rule(v[1], Integer.parseInt(v[2]))))
                    .classify(v[0].getBytes(StandardCharsets.UTF_8)).text());
    }
    @Test
    void boundsKeyAndGroupAndDoesNotDecodeInvalidUtf8() {
        var classifier = new DistributionClassifier(List.of(rule(":", 1)));
        assertEquals("BINARY_KEY", classifier.classify(new byte[]{(byte) 0xff}).text());
        assertEquals("KEY_TOO_LONG", classifier.classify(new byte[4097]).text());
        assertEquals("GROUP_TOO_LONG",
                classifier.classify(("x".repeat(257) + ":id").getBytes(StandardCharsets.UTF_8)).text());
    }
    @Test
    void firstRuleWinsAndSystemNamesCannotCollide() {
        var rules = List.of(new DistributionRule("first", "OTHER", "order:", DistributionRule.Kind.FIXED, null, 0),
                rule(":", 1));
        var group = new DistributionClassifier(rules).classify("order:123".getBytes(StandardCharsets.UTF_8));
        assertFalse(group.system());
        assertEquals("first", group.ruleId());
        assertNotEquals(group, DistributionClassifier.Group.system("OTHER"));
    }
    @Test
    void fixedCapacityPreservesKnownGroupsAndCountsOverflow() {
        var counter = new DistributionCounter(DistributionCounter.Mode.FIXED, 2,
                List.of(new DistributionRule("r", "known", "x", DistributionRule.Kind.FIXED, null, 0)));
        for (int i = 0; i < 10000; i++)
            counter.accept(new DistributionClassifier.Group("r", "g" + i, false));
        counter.accept(new DistributionClassifier.Group("r", "known", false));
        assertEquals(2, counter.groupCount());
        assertEquals(10001, counter.observed());
        assertEquals(9999, counter.snapshot().stream().filter(e -> e.group().text().equals("GROUP_LIMIT")).findFirst()
                .orElseThrow().count());
    }
    @Test
    void topKBoundsErrorAndRestoresExactly() {
        var counter = new DistributionCounter(DistributionCounter.Mode.TOP_K, 10, List.of());
        Map<String, Long> actual = new HashMap<>();
        for (int i = 0; i < 10000; i++) {
            String key = i % 2 == 0 ? "hot" : "g" + (i % 101);
            actual.merge(key, 1L, Long::sum);
            counter.accept(new DistributionClassifier.Group("r", key, false));
        }
        assertEquals(10, counter.groupCount());
        for (var e : counter.snapshot()) {
            long exact = actual.get(e.group().text());
            assertTrue(e.lowerBound() <= exact && exact <= e.count());
        }
        var restored = new DistributionCounter(DistributionCounter.Mode.TOP_K, 10, List.of());
        restored.restore(counter.observed(), counter.snapshot());
        var next = new DistributionClassifier.Group("r", "hot", false);
        counter.accept(next);
        restored.accept(next);
        assertEquals(counter.snapshot(), restored.snapshot());
    }
    @Test
    void millionDistinctPrefixesDoNotGrowCounters() {
        var counter = new DistributionCounter(DistributionCounter.Mode.TOP_K, 1000, List.of());
        for (int i = 0; i < 1_000_000; i++)
            counter.accept(new DistributionClassifier.Group("r", "prefix-" + i, false));
        assertEquals(1000, counter.groupCount());
        assertEquals(1_000_000, counter.observed());
    }
}
