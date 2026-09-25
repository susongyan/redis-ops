package io.github.susongyan.redisops.platform.application.operation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.susongyan.redisops.platform.domain.asset.*;
import io.github.susongyan.redisops.platform.domain.audit.AuditRepository;
import io.github.susongyan.redisops.platform.domain.operation.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsoleConfirmationTest {
    final OperationRepository repo = mock(OperationRepository.class);
    final ClusterRepository clusters = mock(ClusterRepository.class);
    final RedisOperationPort redis = mock(RedisOperationPort.class);
    final AuditRepository audits = mock(AuditRepository.class);
    final RedisOperationService service = new RedisOperationService(repo, clusters, redis, new ObjectMapper(), audits);
    final Map<Long, RedisOperation> records = new HashMap<>();
    boolean failFinish;

    @BeforeEach
    void setup() {
        when(clusters.findById(1)).thenReturn(Optional.of(new RedisCluster(1L, "test", "TEST", null, "test", null, null,
                ClusterMode.STANDALONE, null, "localhost:1", null, ClusterStatus.ACTIVE, 0, Instant.EPOCH,
                Instant.EPOCH)));
        when(repo.findByNumber(anyString())).thenAnswer(
                c -> records.values().stream().filter(x -> x.operationNo().equals(c.getArgument(0))).findFirst());
        when(repo.find(anyLong())).thenAnswer(c -> Optional.ofNullable(records.get(c.getArgument(0))));
        when(repo.save(any())).thenAnswer(c -> {
            var x = (RedisOperation) c.getArgument(0);
            var saved = copy(x, 1L, 0);
            records.put(1L, saved);
            return saved;
        });
        when(repo.update(any(), anyLong())).thenAnswer(c -> {
            RedisOperation x = c.getArgument(0);
            long version = c.getArgument(1);
            if (failFinish && "SUCCEEDED".equals(x.status()))
                throw new IllegalStateException("database unavailable");
            if (records.get(x.id()).version() != version)
                return false;
            records.put(x.id(), copy(x, x.id(), version + 1));
            return true;
        });
        when(redis.execute(anyLong(), anyInt(), anyString(), anyList(), anyInt()))
                .thenReturn(new RedisOperationPort.OperationResult(true, "string", "private-value", 13, -1, null));
    }
    OperationCommand command(String access, String risk, String action, long version) {
        return new OperationCommand(1L, "ECHO", 1, "TEST", access, risk, true,
                "[{\"name\":\"message\",\"type\":\"TEXT\",\"required\":true}]", 0, "NO_KEY", action, 4096,
                "[\"key\"]", "CREATE_ALLOWED", false, "test", "user:1", version, Instant.EPOCH, Instant.EPOCH);
    }
    static RedisOperation copy(RedisOperation x, long id, long version) {
        return new RedisOperation(id, x.operationNo(), x.clusterId(), x.databaseNo(), x.commandName(),
                x.argumentsJson(),
                x.argumentsDigest(), x.accessMode(), x.riskLevel(), x.status(), x.previewJson(), x.approvalNote(),
                x.operatorName(), x.approverName(), x.resultJson(), version, x.createdAt(), x.updatedAt());
    }
    @Test
    void directQueryReturnsValueOnlyToInitialCallerAndIdempotencyNeverReexecutes() {
        when(repo.commands(true, true)).thenReturn(List.of(command("READ", "LOW", "DIRECT", 0)));
        var first = service.request(1, 0, "ECHO", List.of("private-key"), "user:1", "request-1");
        assertEquals("SUCCEEDED", first.status());
        assertTrue(first.resultJson().contains("private-value"));
        assertFalse(records.get(1L).resultJson().contains("private-value"));
        assertFalse(records.get(1L).previewJson().contains("private-key"));
        service.request(1, 0, "ECHO", List.of("private-key"), "user:1", "request-1");
        verify(redis, times(1)).execute(anyLong(), anyInt(), anyString(), anyList(), anyInt());
        assertThrows(IllegalArgumentException.class,
                () -> service.request(1, 0, "ECHO", List.of("different"), "user:1", "request-1"));
    }
    @Test
    void writesCannotBypassConfirmationEvenWhenConfiguredDirect() {
        when(repo.commands(true, true)).thenReturn(List.of(command("WRITE", "LOW", "DIRECT", 0)));
        var pending = service.request(1, 0, "ECHO", List.of("value"), "user:1", "request-1");
        assertEquals("PENDING_CONFIRMATION", pending.status());
        assertThrows(IllegalArgumentException.class,
                () -> service.execute(1, pending.version(), "user:1", List.of("value")));
        verifyNoInteractions(redis);
        var confirmed = service.confirm(1, pending.version(), "user:1");
        assertThrows(IllegalArgumentException.class,
                () -> service.execute(1, confirmed.version(), "user:2", List.of("value")));
        assertThrows(IllegalArgumentException.class,
                () -> service.execute(1, confirmed.version(), "user:1", List.of("changed")));
        assertEquals("SUCCEEDED", service.execute(1, confirmed.version(), "user:1", List.of("value")).status());
        assertThrows(IllegalArgumentException.class,
                () -> service.execute(1, confirmed.version(), "user:1", List.of("value")));
    }
    @Test
    void highRiskRequiresTargetNameAndReasonAndChangedPolicyInvalidatesConfirmation() {
        when(repo.commands(true, true)).thenReturn(List.of(command("WRITE", "HIGH", "DIRECT", 0)));
        var pending = service.request(1, 0, "ECHO", List.of("value"), "user:1", "request-1");
        assertThrows(IllegalArgumentException.class, () -> service.confirm(1, pending.version(), "user:1"));
        assertThrows(IllegalArgumentException.class,
                () -> service.confirm(1, pending.version(), "user:1", "wrong", "maintenance"));
        var confirmed = service.confirm(1, pending.version(), "user:1", "test", "maintenance");
        when(repo.commands(true, true)).thenReturn(List.of(command("WRITE", "HIGH", "DIRECT", 1)));
        assertThrows(IllegalArgumentException.class,
                () -> service.execute(1, confirmed.version(), "user:1", List.of("value")));
        verifyNoInteractions(redis);
    }
    @Test
    void persistedClaimSurvivesResultFailureAndRequestReplayDoesNotSendAgain() {
        when(repo.commands(true, true)).thenReturn(List.of(command("READ", "LOW", "DIRECT", 0)));
        failFinish = true;
        assertThrows(IllegalStateException.class,
                () -> service.request(1, 0, "ECHO", List.of("value"), "user:1", "request-1"));
        assertEquals("EXECUTING", records.get(1L).status());
        assertEquals("EXECUTING", service.request(1, 0, "ECHO", List.of("value"), "user:1", "request-1").status());
        verify(redis, times(1)).execute(anyLong(), anyInt(), anyString(), anyList(), anyInt());
    }
}
