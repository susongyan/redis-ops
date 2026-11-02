package io.github.susongyan.redisops.platform.application.history;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.susongyan.redisops.platform.common.BusinessException;
import io.github.susongyan.redisops.platform.domain.audit.AuditRepository;
import io.github.susongyan.redisops.platform.domain.history.ExecutionRecordRepository;
import io.github.susongyan.redisops.platform.domain.history.ExecutionRecordRepository.*;
import org.junit.jupiter.api.Test;

class ExecutionRecordServiceTest {
    final ExecutionRecordRepository records = mock(ExecutionRecordRepository.class);
    final AuditRepository audits = mock(AuditRepository.class);
    final ExecutionRecordService service = new ExecutionRecordService(records, audits);
    @Test
    void deletesTerminalRecordsAndAuditsOnlyIdentityAndState() {
        for (Type type : Type.values()) {
            String state = type == Type.VALIDATION ? "PASSED" : "COMPLETED";
            when(records.lock(type, 1)).thenReturn(new Snapshot(2, state, false, false));
            when(records.delete(type, 1, 2)).thenReturn(true);
            service.delete(type, 1, 2, "user:7");
            verify(records).purgeDetails(type, 1);
            verify(audits).append(eq("user:7"), eq("EXECUTION_RECORD_DELETE"), eq(type.name()), eq("1"), eq("SUCCESS"),
                    argThat(json -> json.contains("状态") && !json.contains("password") && !json.contains("keyName")));
        }
    }

    @Test
    void deletePurgesDetailsInTransactionBeforeSoftDeletingTheTask() {
        when(records.lock(Type.RISK_SCAN, 1)).thenReturn(new Snapshot(2, "COMPLETED", false, false));
        when(records.purgeDetails(Type.RISK_SCAN, 1)).thenReturn(42);
        when(records.delete(Type.RISK_SCAN, 1, 2)).thenReturn(true);
        service.delete(Type.RISK_SCAN, 1, 2, "user:7");
        org.mockito.InOrder order = inOrder(records);
        order.verify(records).purgeDetails(Type.RISK_SCAN, 1);
        order.verify(records).delete(Type.RISK_SCAN, 1, 2);
        verify(audits).append(eq("user:7"), eq("EXECUTION_RECORD_DELETE"), eq("RISK_SCAN"), eq("1"), eq("SUCCESS"),
                argThat(json -> json.contains("清理明细行")));
    }
    @Test
    void rejectsActivePausedQueuedAndDraftStates() {
        for (Type type : Type.values())
            for (String status : new String[]{"RUNNING", "PAUSED", "QUEUED", "CREATED", "CHECKING", "READY"}) {
                when(records.lock(type, 1)).thenReturn(new Snapshot(2, status, false, false));
                assertEquals("RECORD_NOT_FINISHED",
                        assertThrows(BusinessException.class, () -> service.delete(type, 1, 2, "user:7")).code());
            }
        verify(records, never()).delete(any(), anyLong(), anyLong());
        verify(records, never()).purgeDetails(any(), anyLong());
        verifyNoInteractions(audits);
    }
    @Test
    void protectsVersionJobsAndSyncEvidence() {
        when(records.lock(Type.RISK_SCAN, 1)).thenReturn(new Snapshot(3, "COMPLETED", false, false));
        assertEquals("CONCURRENT_MODIFICATION",
                assertThrows(BusinessException.class, () -> service.delete(Type.RISK_SCAN, 1, 2, "user:7")).code());
        when(records.lock(Type.RISK_SCAN, 1)).thenReturn(new Snapshot(2, "CANCELLED", false, true));
        assertEquals("RECORD_EXECUTING",
                assertThrows(BusinessException.class, () -> service.delete(Type.RISK_SCAN, 1, 2, "user:7")).code());
        when(records.lock(Type.VALIDATION, 1)).thenReturn(new Snapshot(2, "PASSED", true, false));
        assertEquals("RECORD_REFERENCED",
                assertThrows(BusinessException.class, () -> service.delete(Type.VALIDATION, 1, 2, "user:7")).code());
        verify(records, never()).delete(any(), anyLong(), anyLong());
        verify(records, never()).purgeDetails(any(), anyLong());
        verifyNoInteractions(audits);
    }
    @Test
    void failedConditionalDeleteDoesNotAuditSuccess() {
        when(records.lock(Type.ANALYSIS, 1)).thenReturn(new Snapshot(0, "COMPLETED", false, false));
        assertThrows(BusinessException.class, () -> service.delete(Type.ANALYSIS, 1, 0, "user:7"));
        verifyNoInteractions(audits);
    }
}
