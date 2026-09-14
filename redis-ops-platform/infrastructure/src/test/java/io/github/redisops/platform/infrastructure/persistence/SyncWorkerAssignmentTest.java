package io.github.redisops.platform.infrastructure.persistence;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SyncWorkerAssignmentTest {
    @Test
    void batchSqlUsesDatabaseClockAndRejectsStaleMachineIdentity() {
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.addMapper(SyncMapper.class);
        var query = configuration.getMappedStatement(SyncMapper.class.getName() + ".findWorkerAssignments")
                .getBoundSql(Map.of("taskIds", List.of(1L, 2L)));
        for (String fragment : List.of("LEFT JOIN sync_runtime", "worker_ip_runtime_id=r.runtime_id",
                "r.lease_until <= CURRENT_TIMESTAMP(3)", "'EXPIRED'", "'UNASSIGNED'"))
            assertTrue(query.getSql().contains(fragment), fragment);
        assertEquals(2, query.getParameterMappings().size());
    }

    @Test
    void emptyBatchDoesNotIssueInvalidSql() {
        SyncMapper mapper = mock(SyncMapper.class);
        assertTrue(new MyBatisSyncRepository(mapper).findWorkerAssignments(List.of()).isEmpty());
        verifyNoInteractions(mapper);
    }
}
