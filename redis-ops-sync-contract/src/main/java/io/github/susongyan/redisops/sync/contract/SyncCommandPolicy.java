package io.github.susongyan.redisops.sync.contract;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public record SyncCommandPolicy(boolean allowDestructiveCommands, boolean allowSafeSplit,
        Set<String> additionalBlockedCommands, String policyVersion) {
    public static final String CURRENT_VERSION = "v1";
    public static final String MULTI_KEY_VERSION = "v2";
    public static final String TRANSACTION_VERSION = "v3";
    public static final Set<String> SUPPORTED_VERSIONS = Set.of(CURRENT_VERSION, MULTI_KEY_VERSION,
            TRANSACTION_VERSION);

    public SyncCommandPolicy {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (additionalBlockedCommands != null) {
            if (additionalBlockedCommands.size() > 100)
                throw new IllegalArgumentException("additionalBlockedCommands must contain at most 100 commands");
            for (String command : additionalBlockedCommands) {
                if (command == null || !command.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))
                    throw new IllegalArgumentException("invalid Redis command name: " + command);
                normalized.add(command.toUpperCase(Locale.ROOT));
            }
        }
        additionalBlockedCommands = Set.copyOf(normalized);
        policyVersion = policyVersion == null || policyVersion.isBlank() ? CURRENT_VERSION : policyVersion;
        if (!SUPPORTED_VERSIONS.contains(policyVersion))
            throw new IllegalArgumentException("unsupported sync command policy version: " + policyVersion);
    }

    public static SyncCommandPolicy strict() {
        return new SyncCommandPolicy(false, true, Set.of(), CURRENT_VERSION);
    }

    public boolean additionallyBlocks(String command) {
        return additionalBlockedCommands.contains(command.toUpperCase(Locale.ROOT));
    }
    public boolean supportsMultiKey() {
        return MULTI_KEY_VERSION.equals(policyVersion) || supportsTransactions();
    }
    public boolean supportsTransactions() {
        return TRANSACTION_VERSION.equals(policyVersion);
    }
}
