import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import io.github.susongyan.redisops.platform.infrastructure.persistence.*;
import io.github.susongyan.redisops.platform.infrastructure.distribution.*;
import io.github.susongyan.redisops.platform.domain.history.ExecutionRecordRepository.Type;
import io.github.susongyan.redisops.platform.domain.analysis.*;
import io.github.susongyan.redisops.platform.common.BusinessException;

/** Disposable restored MySQL only; writes roll back and never access Redis. */
public class ExecutionRecordDatabase {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        try (var c = DriverManager.getConnection(System.getenv("BASELINE_JDBC_URL"), "root", System.getenv("BASELINE_DB_PASSWORD"))) {
            c.setAutoCommit(false);
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(c, true));
            var records = new JdbcExecutionRecordRepository(jdbc);
            var cfg = new Configuration(); cfg.setMapUnderscoreToCamelCase(true);
            for (var mapper : List.of(RiskScanMapper.class, ValidationMapper.class, AnalysisRunMapper.class)) cfg.addMapper(mapper);
            try (var session = new SqlSessionFactoryBuilder().build(cfg).openSession(c)) {
                // Minimal assets exist only in this rollback transaction, never in delivered seed data.
                jdbc.update("INSERT INTO redis_cluster(id,name,environment,owner,mode,endpoint) VALUES(1,'delete-fixture-source','TEST','fixture','STANDALONE','127.0.0.1:1'),(2,'delete-fixture-target','TEST','fixture','STANDALONE','127.0.0.1:2')");
                jdbc.update("INSERT INTO scan_task(id,task_no,cluster_id,large_key_threshold_bytes,scan_rate_per_second,max_findings,status) VALUES(920001,'DELETE-RISK',1,1024,100,100,'COMPLETED')");
                jdbc.update("INSERT INTO scan_run(task_id,run_no,status) VALUES(920001,'DELETE-RUN','COMPLETED')");
                jdbc.update("INSERT INTO validation_task(id,task_no,source_cluster_id,target_cluster_id,source_db,target_db,strictness,include_patterns_json,exclude_patterns_json,sample_seed,sample_limit,ttl_tolerance_seconds,large_key_threshold_bytes,max_deep_compare_bytes,chunk_bytes,max_elements_per_key,status) VALUES(920002,'DELETE-VALIDATION',1,2,0,0,'REPORT','[]','[]','seed',100,5,1024,1024,1024,100,'PASSED')");
                jdbc.update("INSERT INTO key_distribution_task(id,cluster_id,spec_json,status) VALUES(920003,1,'{}','COMPLETED')");
                jdbc.update("INSERT INTO key_distribution_runtime(cluster_id,task_id,owner) VALUES(1,920003,'executor')");
                check(records.lock(Type.KEY_DISTRIBUTION,920003).executing(), "distribution lease protects terminal task");
                jdbc.update("UPDATE key_distribution_runtime SET owner=NULL,task_id=NULL WHERE cluster_id=1");
                var analyses = new MyBatisAnalysisRunRepository(session.getMapper(AnalysisRunMapper.class), new ObjectMapper());
                var result = new AnalysisResult("delete-fixture", AnalysisProtocol.RULE,"fixture","COMPLETED","fixture result",0.5,List.of(),List.of(),Instant.now());
                var run = analyses.save(new AnalysisRun(null,new AnalysisRequest(AnalysisType.RISK_SCAN,"RISK_SCAN_TASK","920001", java.util.Map.of(),List.of(),List.of()),result));
                check(run.version()==0,"AI immutable result starts at version zero");
                jdbc.update("INSERT INTO async_job(job_type,biz_id,payload_json,status,idempotency_key) VALUES('DATA_VALIDATION',920002,'{}','RUNNING','delete-fixture-job')");
                check(records.lock(Type.VALIDATION,920002).executing(),"correct DATA_VALIDATION job type protected");
                jdbc.update("UPDATE async_job SET status='SUCCEEDED' WHERE biz_id=920002 AND job_type='DATA_VALIDATION'");
                for (var type : Type.values()) {
                    long id = switch(type) { case RISK_SCAN -> 920001; case VALIDATION -> 920002; case KEY_DISTRIBUTION -> 920003; case ANALYSIS -> run.id(); };
                    var snapshot = records.lock(type,id);
                    check(type.canDelete(snapshot.status()) && !snapshot.executing(),"terminal snapshot "+type);
                    check(!records.delete(type,id,snapshot.version()+1),"stale version rejected "+type);
                    check(records.delete(type,id,snapshot.version()),"conditional deletion "+type);
                    check(!records.delete(type,id,snapshot.version()),"double delete rejected "+type);
                    try { records.lock(type,id); throw new AssertionError("deleted record reachable"); } catch(BusinessException expected) { check(expected.code().equals("RESOURCE_NOT_FOUND"),"missing record code"); }
                }
                session.clearCache();
                check(session.getMapper(RiskScanMapper.class).findTask(920001)==null,"risk detail hidden");
                check(session.getMapper(RiskScanMapper.class).findTasks().stream().noneMatch(t->t.id()==920001),"risk list hidden");
                check(session.getMapper(ValidationMapper.class).findTask(920002)==null,"validation detail hidden");
                check(session.getMapper(ValidationMapper.class).findTasks().stream().noneMatch(t->t.id()==920002),"validation list hidden");
                check(analyses.findById(run.id()).isEmpty(),"analysis detail hidden");
                check(analyses.find(null,null,1,100).stream().noneMatch(t->t.id().equals(run.id())),"analysis list hidden");
                var distributions = new JdbcDistributionRepository(jdbc,new DistributionJson(new ObjectMapper()));
                check(distributions.list(1,100).items().stream().noneMatch(t->t.id()==920003),"distribution list/count hidden");
                check(jdbc.queryForObject("SELECT COUNT(*) FROM scan_run WHERE task_id=920001",Long.class)==1,"linked results retained");
                c.rollback();
            }
        }
        System.out.println("Read-only record deletion MySQL roundtrip passed");
    }
}
