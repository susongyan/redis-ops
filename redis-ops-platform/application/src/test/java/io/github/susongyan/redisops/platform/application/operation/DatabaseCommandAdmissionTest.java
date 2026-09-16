package io.github.susongyan.redisops.platform.application.operation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.susongyan.redisops.platform.domain.operation.*;
import io.github.susongyan.redisops.platform.domain.asset.*;
import io.github.susongyan.redisops.platform.domain.audit.AuditRepository;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseCommandAdmissionTest {
    OperationRepository repo = mock(OperationRepository.class);
    AuditRepository audit = mock(AuditRepository.class);
    ClusterRepository clusters = mock(ClusterRepository.class);
    RedisOperationPort redis = mock(RedisOperationPort.class);
    ObjectMapper json = new ObjectMapper();
    RedisOperationService service = new RedisOperationService(repo, clusters, redis, json, audit);
    OperationCommand command(long version) {
        return new OperationCommand(1L, "ECHO", 1, "CUSTOM", "READ", "LOW", true,
                "[{\"name\":\"message\",\"type\":\"TEXT\",\"required\":true}]", 0, "NO_KEY", "DIRECT", 4096,
                "[\"key\"]", "CREATE_ALLOWED", false, "test", "user:1", version, Instant.now(), Instant.now());
    }
    void cluster(ClusterMode mode) {
        when(clusters.findById(1)).thenReturn(Optional.of(new RedisCluster(1L, "test", "TEST", null, "test", null, null,
                mode, null, "localhost:1", null, ClusterStatus.ACTIVE, 0, Instant.now(), Instant.now())));
    }
    @Test
    void databaseDefinitionAdmitsNewCommandAndRejectsMissingCommand() {
        cluster(ClusterMode.STANDALONE);
        when(repo.command("ECHO")).thenReturn(Optional.of(command(0)));
        assertEquals("ECHO", service.preview(1, 0, "echo", List.of("hello")).command());
        assertThrows(IllegalArgumentException.class, () -> service.preview(1, 0, "NOT_CONFIGURED", List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.preview(1, 0, "ECHO", List.of("one", "extra")));
        verifyNoInteractions(redis);
    }
    @Test
    void clusterKeylessRoutingIsRejectedWithoutRedisAccess() {
        cluster(ClusterMode.CLUSTER);
        when(repo.command("ECHO")).thenReturn(Optional.of(command(0)));
        assertThrows(IllegalArgumentException.class, () -> service.preview(1, 0, "ECHO", List.of("hello")));
        verifyNoInteractions(redis);
    }
    @Test
    void confirmationCannotBeSkipped() {
        var now = Instant.now();
        var operation = new RedisOperation(1L, "OP", 1, 0, "ECHO", "[]", "digest", "READ", "LOW",
                "PENDING_CONFIRMATION", "{\"definitionId\":1,\"definitionVersion\":0}", null, "user:1", null, null, 0,
                now, now);
        when(repo.find(1)).thenReturn(Optional.of(operation));
        assertThrows(IllegalArgumentException.class, () -> service.execute(1, 0, "user:1", List.of("hello")));
        verifyNoInteractions(redis);
    }
    @Test
    void changedOrDisabledDefinitionCannotExecutePreviouslyApprovedRequest() throws Exception {
        var now = Instant.now();
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest("[\"hello\"]".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var operation = new RedisOperation(1L, "OP", 1, 0, "ECHO", "[]", digest, "READ", "LOW", "APPROVED",
                "{\"definitionId\":1,\"definitionVersion\":0}", null, "user:1", null, null, 0, now, now);
        when(repo.find(1)).thenReturn(Optional.of(operation));
        when(repo.command("ECHO")).thenReturn(Optional.of(command(1)));
        assertEquals("COMMAND_CHANGED_RECREATE_OPERATION",
                assertThrows(IllegalArgumentException.class, () -> service.execute(1, 0, "user:1", List.of("hello")))
                        .getMessage());
        when(repo.command("ECHO")).thenReturn(Optional.empty());
        assertEquals("COMMAND_NOT_ALLOWED",
                assertThrows(IllegalArgumentException.class, () -> service.execute(1, 0, "user:1", List.of("hello")))
                        .getMessage());
        verifyNoInteractions(redis);
    }
    @Test
    void newCommandsDefaultDisabledAndNamesAreNotHardcoded() {
        var catalog = new CommandCatalogService(repo, audit, json);
        when(repo.commands(true, true)).thenReturn(List.of());
        when(repo.createCommand(any())).thenAnswer(call -> {
            OperationCommand c = call.getArgument(0);
            assertFalse(c.enabled());
            assertEquals("CUSTOM.DO", c.commandName());
            return command(0);
        });
        catalog.define(null, 0, new CommandCatalogService.Definition("CUSTOM.DO", "CUSTOM", "READ", "LOW", true, "[]",
                0, "NO_KEY", "DIRECT", 4096, "approved by ops"), "user:1");
        verify(audit).append(eq("user:1"), eq("OPERATION_COMMAND_CREATE"), eq("OPERATION_COMMAND"), eq("1"),
                eq("SUCCESS"), contains("命令配置"));
    }
}
