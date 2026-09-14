package io.github.redisops.platform.domain.distribution;

import java.util.*;
import io.github.redisops.platform.domain.distribution.DistributionClassifier.Group;

/** Bounded Space-Saving or admission-capped exact observation counters; never stores raw keys. */
public final class DistributionCounter {
    public enum Mode {
        FIXED, TOP_K
    }
    public record Entry(Group group, long count, long error) {
        public long lowerBound() {
            return count - error;
        }
    }
    private static final Set<String> SYSTEM = Set.of("KEY_TOO_LONG", "BINARY_KEY", "STRUCTURE_MISMATCH",
            "GROUP_TOO_LONG", "OTHER", "GROUP_LIMIT");
    private final Mode mode;
    private final int capacity;
    private final Map<Group, Entry> entries = new LinkedHashMap<>();
    private final Map<Group, Entry> system = new LinkedHashMap<>();
    private final NavigableSet<Entry> ordered = new TreeSet<>(Comparator.comparingLong(Entry::count)
            .thenComparing(e -> e.group().ruleId()).thenComparing(e -> e.group().text()));
    private long observed;
    public DistributionCounter(Mode mode, int capacity, List<DistributionRule> rules) {
        if (mode == null || capacity < 1 || capacity > 1000)
            throw new IllegalArgumentException("INVALID_DISTRIBUTION_CAPACITY");
        this.mode = mode;
        this.capacity = capacity;
        if (mode == Mode.FIXED)
            for (var rule : rules)
                if (rule.kind() == DistributionRule.Kind.FIXED) {
                    if (entries.size() >= capacity)
                        throw new IllegalArgumentException("FIXED_GROUPS_EXCEED_CAPACITY");
                    Group group = new Group(rule.id(), rule.name(), false);
                    entries.put(group, new Entry(group, 0, 0));
                }
    }
    public void accept(Group group) {
        if (group == null || DistributionRule.bytes(group.text()) > 256 || DistributionRule.bytes(group.ruleId()) > 32
                || group.system() && !SYSTEM.contains(group.text()))
            throw new IllegalArgumentException("INVALID_DISTRIBUTION_GROUP");
        observed = Math.addExact(observed, 1);
        if (group.system()) {
            increment(system, group);
            return;
        }
        if (entries.containsKey(group)) {
            ordered.remove(entries.get(group));
            increment(entries, group);
            if (mode == Mode.TOP_K)
                ordered.add(entries.get(group));
            return;
        }
        if (entries.size() < capacity) {
            entries.put(group, new Entry(group, 1, 0));
            if (mode == Mode.TOP_K)
                ordered.add(entries.get(group));
            return;
        }
        if (mode == Mode.FIXED) {
            increment(system, Group.system("GROUP_LIMIT"));
            return;
        }
        Entry minimum = ordered.pollFirst();
        entries.remove(minimum.group());
        entries.put(group, new Entry(group, Math.addExact(minimum.count(), 1), minimum.count()));
        ordered.add(entries.get(group));
    }
    private static void increment(Map<Group, Entry> map, Group group) {
        Entry before = map.getOrDefault(group, new Entry(group, 0, 0));
        map.put(group, new Entry(group, Math.addExact(before.count(), 1), before.error()));
    }
    public long observed() {
        return observed;
    }
    public int groupCount() {
        return entries.size();
    }
    public List<Entry> snapshot() {
        List<Entry> result = new ArrayList<>(entries.values());
        result.addAll(system.values());
        result.sort(Comparator.comparingLong(Entry::count).reversed());
        return List.copyOf(result);
    }
    public void restore(long count, List<Entry> saved) {
        if (count < 0 || saved == null || saved.size() > capacity + SYSTEM.size())
            throw new IllegalArgumentException("INVALID_DISTRIBUTION_CHECKPOINT");
        entries.clear();
        system.clear();
        ordered.clear();
        long sum = 0;
        for (Entry e : saved) {
            Group g = e.group();
            if (g == null || g.ruleId() == null || g.text() == null || DistributionRule.bytes(g.ruleId()) > 32
                    || DistributionRule.bytes(g.text()) > 256 || e.error() < 0 || e.count() < e.error()
                    || (mode == Mode.FIXED && e.error() != 0)
                    || g.system() && (!SYSTEM.contains(g.text()) || e.error() != 0))
                throw new IllegalArgumentException("INVALID_DISTRIBUTION_CHECKPOINT");
            var target = g.system() ? system : entries;
            if (target.put(g, e) != null)
                throw new IllegalArgumentException("DUPLICATE_DISTRIBUTION_GROUP");
            sum = Math.addExact(sum, e.count());
        }
        if (entries.size() > capacity || sum != count)
            throw new IllegalArgumentException("INVALID_DISTRIBUTION_CHECKPOINT");
        observed = count;
        if (mode == Mode.TOP_K)
            ordered.addAll(entries.values());
    }
}
