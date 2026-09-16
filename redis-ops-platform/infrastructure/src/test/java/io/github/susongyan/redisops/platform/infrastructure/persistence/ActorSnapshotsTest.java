package io.github.susongyan.redisops.platform.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActorSnapshotsTest {
    @Test
    void capturesNamesAtWriteTimeAndDoesNotInventSystemUsers() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var result = mock(ResultSet.class);
        when(result.getLong("id")).thenReturn(7L);
        when(result.getString("login_name")).thenReturn("alice");
        when(result.getString("display_name")).thenReturn("Alice");
        when(jdbc.query(anyString(), any(RowMapper.class), eq(7L)))
                .thenAnswer(invocation -> List.of(((RowMapper<?>) invocation.getArgument(1)).mapRow(result, 0)));
        var actors = new ActorSnapshots(jdbc, new ObjectMapper());
        var before = actors.capture("user:7");
        when(result.getString("display_name")).thenReturn("New name");
        var after = actors.capture("user:7");
        assertEquals("Alice", new ObjectMapper().readTree(before).get("displayName").asText());
        assertEquals("New name", new ObjectMapper().readTree(after).get("displayName").asText());
        assertEquals(7, new ObjectMapper().readTree(before).get("userId").asLong());
        assertNull(actors.capture("worker:local"));
        assertNull(actors.capture(null));
        verify(jdbc, times(2)).query(anyString(), any(RowMapper.class), eq(7L));
    }
    @Test
    void unknownUserFailsClosed() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(7L))).thenReturn(List.of());
        assertThrows(IllegalStateException.class, () -> new ActorSnapshots(jdbc, new ObjectMapper()).capture("user:7"));
    }
    @Test
    void auditWritesSnapshotAndReadsWithoutResolvingCurrentNames() {
        var mapper = mock(AssetMapper.class);
        var actors = mock(ActorSnapshots.class);
        when(actors.capture("user:7")).thenReturn("{\"login\":\"alice\"}");
        var repo = new MyBatisAuditRepository(mapper, actors);
        repo.append("user:7", "UPDATE", "CLUSTER", "1", "SUCCESS");
        verify(mapper).appendAudit("user:7", "UPDATE", "CLUSTER", "1", "SUCCESS", "{\"login\":\"alice\"}");
        clearInvocations(actors);
        repo.find(null, null, null, 20);
        verifyNoInteractions(actors);
    }
    @Test
    void mapperQueriesExposeSnapshotColumns() {
        var config = new org.apache.ibatis.session.Configuration();
        for (var type : List.of(AssetMapper.class, SyncMapper.class, OperationMapper.class, AlertMapper.class))
            config.addMapper(type);
        var sql = config.getMappedStatement(AssetMapper.class.getName() + ".findAudits")
                .getBoundSql(java.util.Map.of("operator", "alice", "limit", 20)).getSql();
        assertTrue(sql.contains("$.login"));
        assertTrue(sql.contains("operatorSnapshot"));
    }
}
