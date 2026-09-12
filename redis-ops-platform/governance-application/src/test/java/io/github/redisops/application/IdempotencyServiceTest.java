package io.github.redisops.application;

import io.github.redisops.common.BusinessException;
import io.github.redisops.domain.idempotency.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class IdempotencyServiceTest {
    @Test
    void replaysCompletedRequestWithoutExecutingAgain() {
        MemoryRepository repository = new MemoryRepository();
        IdempotencyService service = new IdempotencyService(repository);
        AtomicInteger calls = new AtomicInteger();
        String first = service.execute("alice", "key-1", "CREATE", "same", () -> "resource-" + calls.incrementAndGet(),
                v -> v, v -> v);
        String replay = service.execute("alice", "key-1", "CREATE", "same", () -> "resource-" + calls.incrementAndGet(),
                v -> v, v -> v);
        assertEquals("resource-1", first);
        assertEquals(first, replay);
        assertEquals(1, calls.get());
    }
    @Test
    void rejectsReusingKeyForDifferentRequest() {
        IdempotencyService service = new IdempotencyService(new MemoryRepository());
        service.execute("alice", "key-1", "CREATE", "first", () -> "1", v -> v, v -> v);
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.execute("alice", "key-1", "CREATE", "second", () -> "2", v -> v, v -> v));
        assertEquals("IDEMPOTENCY_CONFLICT", error.code());
    }
    @Test
    void replaysCompletedVoidRequestWithoutExecutingAgain() {
        IdempotencyService service = new IdempotencyService(new MemoryRepository());
        AtomicInteger calls = new AtomicInteger();
        service.executeVoid("alice", "delete-1", "DELETE", "same", calls::incrementAndGet, "42");
        service.executeVoid("alice", "delete-1", "DELETE", "same", calls::incrementAndGet, "42");
        assertEquals(1, calls.get());
    }
    static class MemoryRepository implements IdempotencyRepository {
        private final Map<String, IdempotencyRecord> data = new HashMap<>();
        private String id(String operator, String key) {
            return operator + ":" + key;
        }
        public boolean tryStart(String operator, String key, String operation, String digest) {
            return data.putIfAbsent(id(operator, key),
                    new IdempotencyRecord(operator, key, operation, digest, "PROCESSING", null)) == null;
        }
        public Optional<IdempotencyRecord> find(String operator, String key) {
            return Optional.ofNullable(data.get(id(operator, key)));
        }
        public void complete(String operator, String key, String resourceId) {
            IdempotencyRecord r = data.get(id(operator, key));
            data.put(id(operator, key),
                    new IdempotencyRecord(operator, key, r.operation(), r.requestDigest(), "COMPLETED", resourceId));
        }
        public void fail(String operator, String key) {
            IdempotencyRecord r = data.get(id(operator, key));
            data.put(id(operator, key),
                    new IdempotencyRecord(operator, key, r.operation(), r.requestDigest(), "FAILED", null));
        }
    }
}
