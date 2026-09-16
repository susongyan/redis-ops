import java.sql.DriverManager;
import java.time.Instant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import io.github.susongyan.redisops.platform.infrastructure.persistence.*;
import io.github.susongyan.redisops.platform.domain.operation.*;

/** Isolated baseline database only. All test writes roll back; no Redis access. */
public class ActorSnapshotDatabase {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        try (var connection = DriverManager.getConnection(System.getenv("BASELINE_JDBC_URL"), "root", System.getenv("BASELINE_DB_PASSWORD"))) {
            connection.setAutoCommit(false);
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            var config = new Configuration();
            config.setMapUnderscoreToCamelCase(true);
            for (var mapper : java.util.List.of(AssetMapper.class, OperationMapper.class, SyncMapper.class, AlertMapper.class)) config.addMapper(mapper);
            try (var session = new SqlSessionFactoryBuilder().build(config).openSession(connection)) {
                jdbc.update("INSERT INTO platform_user(id,display_name,status,role) VALUES(910001,'Alice','ACTIVE','OPERATOR'),(910002,'Bob','ACTIVE','OPERATOR'),(910003,'Carol','ACTIVE','OPERATOR')");
                jdbc.update("INSERT INTO platform_local_credential(user_id,login_name,password_hash) VALUES(910001,'alice','fixture'),(910002,'bob','fixture'),(910003,'carol','fixture')");
                var actors = new ActorSnapshots(jdbc, new ObjectMapper());
                var audit = new MyBatisAuditRepository(session.getMapper(AssetMapper.class), actors);
                audit.append("user:910001", "TEST", "FIXTURE", "1", "SUCCESS");
                jdbc.update("UPDATE platform_user SET display_name='Changed' WHERE id=910001");
                var history = audit.find("alice", "FIXTURE", "1", 20);
                check(history.size()==1 && history.get(0).operatorSnapshot().contains("Alice"), "immutable audit name");
                check(history.get(0).detailsJson()==null, "legacy audit details stay absent");
                audit.append("user:910001", "CHANGE", "FIXTURE", "2", "SUCCESS", "{\"summary\":\"修改命令\",\"changes\":[{\"field\":\"启用\",\"before\":false,\"after\":true}]}");
                session.clearCache();
                var details = new ObjectMapper().readTree(audit.find("alice", "FIXTURE", "2", 20).get(0).detailsJson());
                check(details.get("changes").get(0).get("after").asBoolean(), "audit details JSON roundtrip");
                audit.append("worker:test", "TEST", "FIXTURE", "1", "SUCCESS");
                check(audit.find("worker:test", null, null, 20).get(0).operatorSnapshot()==null, "system fallback");

                var operations = new MyBatisOperationRepository(session.getMapper(OperationMapper.class), actors);
                var now = Instant.now();
                var op = operations.save(new RedisOperation(null,"ACTOR-TEST",1,0,"GET","[]","a".repeat(64),"READ","LOW","PENDING_APPROVAL",null,null,"user:910001",null,null,0,now,now));
                check(op.operatorSnapshot().contains("alice"), "request snapshot");
                var approved = new RedisOperation(op.id(),op.operationNo(),op.clusterId(),0,"GET","[]",op.argumentsDigest(),"READ","LOW","APPROVED",null,null,op.operatorName(),"user:910002",null,0,now,now,op.operatorSnapshot(),null,null,null);
                check(operations.update(approved,0), "approval update");
                session.clearCache();
                op = operations.find(op.id()).orElseThrow();
                check(op.approverSnapshot().contains("bob"), "approver snapshot");
                jdbc.update("UPDATE platform_user SET display_name='Bob changed' WHERE id=910002");
                var executed = new RedisOperation(op.id(),op.operationNo(),1,0,"GET","[]",op.argumentsDigest(),"READ","LOW","SUCCEEDED",null,null,op.operatorName(),op.approverName(),"{}",1,now,now,op.operatorSnapshot(),op.approverSnapshot(),"user:910003",null);
                check(operations.update(executed,1), "execution update");
                session.clearCache();
                op = operations.find(op.id()).orElseThrow();
                check(op.executorSnapshot().contains("carol") && op.approverSnapshot().contains("Bob") && !op.approverSnapshot().contains("changed"), "separate immutable actors");
                check(operations.commands(true,true).size()>0, "command record mapping");
                var command = operations.commands(true,true).get(0);
                var row = new OperationMapper.CommandRow();
                row.commandName=command.commandName(); row.category=command.category(); row.accessMode=command.accessMode(); row.parameterSchemaJson=command.parameterSchemaJson(); row.keyPosition=command.keyPosition(); row.routingPolicy=command.routingPolicy();
                row.id=command.id();row.version=command.version();row.enabled=command.enabled();row.riskLevel=command.riskLevel();row.approvalPolicy=command.approvalPolicy();row.maxValueBytes=command.maxValueBytes();row.allowedDataTypesJson=command.allowedDataTypesJson();row.missingKeyPolicy=command.missingKeyPolicy();row.blockedByDefault=command.blockedByDefault();row.changeReason="test";row.updatedBy="user:910001";row.updatedBySnapshot=actors.capture(row.updatedBy);
                check(session.getMapper(OperationMapper.class).updateCommand(row)==1,"command update snapshot");
                session.clearCache();
                check(operations.commands(true,true).stream().filter(c -> c.id().equals(command.id())).findFirst().orElseThrow().updatedBySnapshot().contains("alice"),"command snapshot read");
                var custom=operations.createCommand(new OperationCommand(null,"EXAMPLE.CUSTOM",1,"CUSTOM","READ","LOW",false,"[]",0,"NO_KEY","CONFIRM",4096,"[\"key\"]","CREATE_ALLOWED",false,"fixture","user:910001",0,now,now));
                check(custom.keyPosition()==0 && !custom.enabled() && custom.updatedBySnapshot().contains("alice"),"custom command insert and snapshot");
                check(operations.command("EXAMPLE.CUSTOM").isEmpty(),"disabled command not admitted");
                var alerts = new MyBatisAlertRepository(session.getMapper(AlertMapper.class), actors);
                jdbc.update("INSERT INTO alert_event(id,rule_id,resource_type,resource_id,status,severity,title) VALUES(910001,1,'FIXTURE','1','OPEN','P1','fixture')");
                check(alerts.acknowledge(910001,"user:910001",0),"acknowledgement");
                check(alerts.findEvent(910001).orElseThrow().acknowledgedBySnapshot().contains("alice"),"alert record mapping");

                jdbc.update("INSERT INTO redis_cluster(id,name,environment,owner,mode,endpoint) VALUES(910001,'actor-fixture','TEST','test','STANDALONE','localhost:1'),(910002,'actor-fixture-target','TEST','test','STANDALONE','localhost:2')");
                jdbc.update("INSERT INTO sync_task(id,task_no,source_cluster_id,target_cluster_id,purpose,sync_mode,status,include_patterns_json,exclude_patterns_json,command_policy_json) VALUES(910001,'actor-fixture',910001,910002,'MIGRATION','FULL_AND_INCREMENTAL','CREATED','[]','[]','{}')");
                var sync = session.getMapper(SyncMapper.class);
                var event = new SyncMapper.EventRow();event.taskId=910001;event.fromStatus=null;event.toStatus="CREATED";event.operator="user:910001";event.message="fixture";event.operatorSnapshot=actors.capture(event.operator);
                sync.insertEvent(event);
                check(sync.findEvents(910001,0,20).get(0).operatorSnapshot().contains("alice"),"sync event mapping");
                jdbc.update("INSERT INTO cluster_relation(id,name,relation_type,primary_cluster_id,standby_cluster_id,desired_rpo_seconds) VALUES(910001,'actor-fixture','PRIMARY_STANDBY',910001,910002,10)");
                var switchovers = new MyBatisSyncRepository(sync, actors);
                var sw = switchovers.saveSwitchover(new io.github.susongyan.redisops.platform.domain.sync.Switchover(null,910001,910001,910002,910001,null,io.github.susongyan.redisops.platform.domain.sync.SwitchoverStatus.WAITING_SOURCE_FENCE,"user:910001",false,null,null,0,now,now,null));
                check(sw.operatorSnapshot().contains("alice"),"switchover record mapping");
                jdbc.update("INSERT INTO audit_log(operator_id,action,resource_type,resource_id,result) VALUES('user:910002','OLD','FIXTURE','1','SUCCESS')");
                session.clearCache();
                check(audit.find("user:910002",null,null,20).get(0).operatorSnapshot()==null,"legacy not backfilled");
                connection.rollback();
            } finally { if (!connection.isClosed()) connection.rollback(); }
        }
        System.out.println("Actor snapshot MySQL mapper roundtrip passed");
    }
}
