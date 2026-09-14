package io.github.redisops.platform.domain.distribution;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

public final class DistributionClassifier {
    public record Group(String ruleId, String text, boolean system) {
        public static Group system(String code) {
            return new Group("", code, true);
        }
    }
    private final List<DistributionRule> rules;
    public DistributionClassifier(List<DistributionRule> rules) {
        if (rules == null || rules.isEmpty() || rules.size() > 32 || rules.stream().anyMatch(Objects::isNull)
                || rules.stream().map(DistributionRule::id).distinct().count() != rules.size())
            throw new IllegalArgumentException("INVALID_DISTRIBUTION_RULES");
        this.rules = List.copyOf(rules);
    }
    public List<DistributionRule> rules() {
        return rules;
    }
    public Group classify(byte[] key) {
        if (key == null || key.length > 4096)
            return Group.system("KEY_TOO_LONG");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(key)).toString();
        } catch (CharacterCodingException e) {
            return Group.system("BINARY_KEY");
        }
        for (DistributionRule rule : rules) {
            if (!text.startsWith(rule.prefix()))
                continue;
            if (rule.kind() == DistributionRule.Kind.FIXED)
                return new Group(rule.id(), rule.name(), false);
            int end = -1, start = 0;
            for (int segment = 0; segment < rule.segments(); segment++) {
                end = text.indexOf(rule.delimiter(), start);
                if (end < 0) {
                    if (segment < rule.segments() - 1)
                        return Group.system("STRUCTURE_MISMATCH");
                    end = text.length();
                    break;
                }
                start = end + rule.delimiter().length();
            }
            String group = text.substring(0, end);
            return DistributionRule.bytes(group) > 256
                    ? Group.system("GROUP_TOO_LONG")
                    : new Group(rule.id(), group, false);
        }
        return Group.system("OTHER");
    }
}
