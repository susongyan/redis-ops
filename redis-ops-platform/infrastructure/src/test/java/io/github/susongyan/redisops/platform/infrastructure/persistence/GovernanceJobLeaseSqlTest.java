package io.github.susongyan.redisops.platform.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class GovernanceJobLeaseSqlTest {
    @Test
    void resumeGateAndRenewalRequireUnexpiredExecutingLease() {
        var config = new Configuration();
        config.addMapper(JobMapper.class);
        var params = Map.of("id", 1L, "owner", "test", "seconds", 600, "type", "TTL_GOVERNANCE", "bizId", 2L);
        String gate = config.getMappedStatement(JobMapper.class.getName() + ".hasExecuting").getBoundSql(params)
                .getSql();
        assertTrue(gate.contains("job_type=? AND biz_id=?"));
        assertTrue(gate.contains("status='RUNNING' AND lease_until>=CURRENT_TIMESTAMP(3)"));
        String renewal = config.getMappedStatement(JobMapper.class.getName() + ".renew").getBoundSql(params).getSql();
        assertTrue(renewal.contains("id=? AND lease_owner=?"));
        assertTrue(renewal.contains("status='RUNNING' AND lease_until>CURRENT_TIMESTAMP(3)"));
    }
}
