package io.github.susongyan.redisops.worker.runtime;

/**
 * At-rest protection mode for the sync spool. NONE writes plaintext spools (default; trusted
 * networks where software AES-GCM would dominate the sync hot path). AES_GCM explicitly seals
 * every RDB chunk and command record — reads always detect the format from the file itself, so a
 * worker switched to NONE still recovers AES_GCM spools written before the switch.
 */
public enum SpoolEncryption {
    AES_GCM, NONE;

    /** Parses the configuration value; blank input falls back to NONE. */
    public static SpoolEncryption parse(String value) {
        if (value == null || value.isBlank())
            return NONE;
        return switch (value.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "AES_GCM", "AES-GCM" -> AES_GCM;
            case "NONE", "PLAIN", "PLAINTEXT" -> NONE;
            default -> throw new IllegalArgumentException(
                    "sync.engine.spool-encryption must be one of AES_GCM, NONE (got: " + value + ")");
        };
    }
}
