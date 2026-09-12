package io.github.redisops.application;

import io.github.redisops.common.BusinessException;
import io.github.redisops.domain.idempotency.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Function;
import java.util.function.Supplier;

@Service
public class IdempotencyService {
    private final IdempotencyRepository records;
    public IdempotencyService(IdempotencyRepository records) {
        this.records = records;
    }

    @Transactional
    public <T> T execute(String operator, String key, String operation, Object request,
            Supplier<T> action, Function<T, String> resourceId, Function<String, T> replay) {
        if (key == null || key.isBlank())
            throw new BusinessException("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key header is required");
        String digest = digest(String.valueOf(request));
        if (!records.tryStart(operator, key, operation, digest)) {
            IdempotencyRecord existing = records.find(operator, key)
                    .orElseThrow(() -> new BusinessException("IDEMPOTENCY_EXPIRED", "idempotency record has expired"));
            if (!existing.operation().equals(operation) || !existing.requestDigest().equals(digest))
                throw new BusinessException("IDEMPOTENCY_CONFLICT",
                        "Idempotency-Key was used with a different request");
            if ("COMPLETED".equals(existing.status()) && existing.resourceId() != null)
                return replay.apply(existing.resourceId());
            throw new BusinessException("REQUEST_IN_PROGRESS",
                    "the request with this Idempotency-Key is still processing");
        }
        try {
            T result = action.get();
            records.complete(operator, key, resourceId.apply(result));
            return result;
        } catch (RuntimeException e) {
            records.fail(operator, key);
            throw e;
        }
    }
    public void executeVoid(String operator, String key, String operation, Object request,
            Runnable action, String resourceId) {
        execute(operator, key, operation, request, () -> {
            action.run();
            return resourceId;
        },
                value -> value, value -> value);
    }
    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
