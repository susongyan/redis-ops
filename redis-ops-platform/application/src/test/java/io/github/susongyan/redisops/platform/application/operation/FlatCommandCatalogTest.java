package io.github.susongyan.redisops.platform.application.operation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.susongyan.redisops.platform.domain.audit.AuditRepository;
import io.github.susongyan.redisops.platform.domain.operation.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FlatCommandCatalogTest {
    final OperationRepository repo = mock(OperationRepository.class);
    final AuditRepository audit = mock(AuditRepository.class);
    final CommandCatalogService service = new CommandCatalogService(repo, audit, new ObjectMapper());
    OperationCommand node(String kind) {
        return new OperationCommand(1L, "TEST", 1, "CUSTOM", "READ", "LOW", true, "[]", 0, "NO_KEY",
                "DIRECT", 4096, "[\"key\"]", "CREATE_ALLOWED", false, "test", "ops", 2,
                Instant.EPOCH, Instant.EPOCH, null, kind, null);
    }
    @Test
    void deletingGroupDetachesButDoesNotDeleteMembers() {
        when(repo.commands(true, true)).thenReturn(List.of(node("CATEGORY")));
        when(repo.deleteCommand(1, 2, "cleanup", "ops")).thenReturn(true);
        service.delete(1, 2, "cleanup", "ops");
        verify(repo).detachGroup(1);
        verify(audit).append(eq("ops"), eq("OPERATION_COMMAND_DELETE"), eq("OPERATION_COMMAND"), eq("1"), eq("SUCCESS"),
                contains("cleanup"));
    }
    @Test
    void attachingCommandUsesCurrentLockedGroupName() {
        when(repo.commands(true, true)).thenReturn(List.of(node("CATEGORY")));
        when(repo.lockGroup(1)).thenReturn(java.util.Optional.of("Renamed"));
        // Return a persisted ID for audit.
        when(repo.createCommand(any())).thenAnswer(call -> {
            OperationCommand c = call.getArgument(0);
            assertEquals("Renamed", c.category());
            assertEquals(1L, c.parentId());
            return node("COMMAND");
        });
        service.define(null, 0, grouped(), "ops");
        verify(repo).lockGroup(1);
    }
    @Test
    void deletedGroupRejectsNewAssociation() {
        when(repo.commands(true, true)).thenReturn(List.of(node("CATEGORY")));
        when(repo.lockGroup(1)).thenReturn(java.util.Optional.empty());
        var error = assertThrows(IllegalArgumentException.class, () -> service.define(null, 0, grouped(), "ops"));
        assertEquals("CATEGORY_PARENT_REQUIRED", error.getMessage());
        verify(repo, never()).createCommand(any());
        verifyNoInteractions(audit);
    }
    private CommandCatalogService.Definition grouped() {
        return new CommandCatalogService.Definition("INFO", "CUSTOM", "READ", "LOW", true,
                "[]", 0, "NO_KEY", "DIRECT", 4096, "test", "COMMAND", 1L);
    }
    @Test
    void deleteRequiresVersionAndReason() {
        when(repo.commands(true, true)).thenReturn(List.of(node("COMMAND")));
        assertThrows(IllegalArgumentException.class, () -> service.delete(1, 1, "cleanup", "ops"));
        assertThrows(IllegalArgumentException.class, () -> service.delete(1, 2, " ", "ops"));
        verify(repo, never()).detachGroup(anyLong());
        verifyNoInteractions(audit);
    }
    @Test
    void subcommandNeedsNoFamilyAndWriteDirectIsStoredAsConfirmation() {
        when(repo.commands(true, true)).thenReturn(List.of());
        when(repo.createCommand(any())).thenAnswer(call -> {
            var c = (OperationCommand) call.getArgument(0);
            assertEquals("CONFIRM", c.approvalPolicy());
            assertTrue(c.enabled());
            assertNull(c.parentId());
            return node("COMMAND");
        });
        service.define(null, 0, new CommandCatalogService.Definition("CUSTOM DO", "CUSTOM", "WRITE", "LOW", true,
                "[{\"name\":\"subcommand\",\"type\":\"TEXT\",\"required\":true,\"literal\":\"DO\"}]", 0, "NO_KEY",
                "DIRECT", 4096, "test", "SUBCOMMAND", null), "ops");
    }
}
