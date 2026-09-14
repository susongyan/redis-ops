package io.github.redisops.platform.domain.identity;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Validate byte length before BCrypt; never silently truncate multi-byte passwords. */
public final class LocalPasswordPolicy {
    private LocalPasswordPolicy() {
    }

    public static void validate(char[] password) {
        if (password == null || password.length > 72 || Character.codePointCount(password, 0, password.length) < 6)
            throw new IllegalArgumentException("INVALID_PASSWORD_LENGTH");
        ByteBuffer encoded = null;
        try {
            encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(password));
            if (encoded.remaining() > 72)
                throw new IllegalArgumentException("INVALID_PASSWORD_LENGTH");
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("INVALID_PASSWORD_ENCODING");
        } finally {
            if (encoded != null && encoded.hasArray())
                Arrays.fill(encoded.array(), (byte) 0);
        }
    }
}
