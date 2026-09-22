package io.github.susongyan.redisops.platform.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class GovernanceCheckpointSqlTest {
    @Test
    void allCheckpointReadsQuoteMysqlReservedCursorAlias() {
        var configuration = new Configuration();
        for (var mapper : List.of(TtlGovernanceMapper.class, CleanupGovernanceMapper.class)) {
            configuration.addMapper(mapper);
            for (var method : List.of("checkpoint", "checkpoints")) {
                String sql = configuration.getMappedStatement(mapper.getName() + "." + method)
                        .getBoundSql(Map.of("runId", 1L, "shardId", "shard-1")).getSql();
                assertTrue(sql.contains("cursor_value AS `cursor`"), sql);
                assertFalse(sql.contains("cursor_value cursor"), sql);
            }
        }
    }
}
