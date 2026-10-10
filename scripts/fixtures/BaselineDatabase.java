import org.flywaydb.core.Flyway;

/** Isolated database snapshot generation only; credentials come from the child environment. */
public class BaselineDatabase {
    public static void main(String[] args) {
        String baselineVersion = args.length > 2 ? args[2] : "26";
        Flyway flyway = Flyway.configure()
                .dataSource(System.getenv("BASELINE_JDBC_URL"), "root", System.getenv("BASELINE_DB_PASSWORD"))
                .locations("filesystem:" + args[1])
                .baselineVersion(baselineVersion)
                .load();
        switch (args[0]) {
            case "upgrade-console" -> {
                Flyway.configure().dataSource(System.getenv("BASELINE_JDBC_URL"), "root", System.getenv("BASELINE_DB_PASSWORD"))
                        .locations("filesystem:" + args[1]).target("34").load().migrate();
                try (var connection = java.sql.DriverManager.getConnection(System.getenv("BASELINE_JDBC_URL"), "root", System.getenv("BASELINE_DB_PASSWORD")); var sql = connection.createStatement()) {
                    sql.executeUpdate("INSERT INTO operation_command_definition(id,command_name,category,access_mode,risk_level,enabled,parameter_schema_json,key_position,routing_policy,approval_policy,node_kind,parent_id,allowed_data_types_json) VALUES "
                            + "(920001,'TEST.GROUP','TEST','READ','LOW',FALSE,'[]',0,'CONTAINER','DIRECT','CATEGORY',NULL,'[\"key\"]'),"
                            + "(920002,'TEST.FAMILY','TEST','READ','LOW',TRUE,'[]',0,'CONTAINER','DIRECT','FAMILY',920001,'[\"key\"]'),"
                            + "(920003,'TEST.FAMILY INFO','TEST','READ','LOW',TRUE,'[]',0,'NO_KEY','DIRECT','SUBCOMMAND',920002,'[\"key\"]'),"
                            + "(920004,'TEST.INHERIT','TEST','READ','LOW',TRUE,'[]',0,'NO_KEY','INHERIT','COMMAND',NULL,'[\"key\"]')");
                    if (flyway.migrate().migrationsExecuted != Integer.parseInt(args[2]) - 34) throw new IllegalStateException("Expected migrations after V34");
                    try (var result=sql.executeQuery("SELECT enabled,parent_id,deleted_at IS NULL FROM operation_command_definition WHERE id=920003")) {
                        if(!result.next() || result.getBoolean(1) || result.getLong(2)!=920001 || !result.getBoolean(3)) throw new IllegalStateException("Flattening broadened disabled ancestor authorization");
                    }
                    try (var result=sql.executeQuery("SELECT enabled,approval_policy FROM operation_command_definition WHERE id=920004")) {
                        if(!result.next() || result.getBoolean(1) || !"DANGER_CONFIRM".equals(result.getString(2))) throw new IllegalStateException("Inherited policy not safely migrated");
                    }
                    try (var result=sql.executeQuery("SELECT deleted_at IS NOT NULL FROM operation_command_definition WHERE id=920002")) {
                        if(!result.next() || !result.getBoolean(1)) throw new IllegalStateException("Family not retired");
                    }
                    flyway.validate();
                    if (Integer.parseInt(args[2]) >= 36) {
                        try (var result=sql.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='operation_catalog_lock'")) {
                            result.next();
                            if(result.getInt(1)!=0)throw new IllegalStateException("Global catalog lock still present");
                        }
                    }
                    if(flyway.migrate().migrationsExecuted!=0)throw new IllegalStateException("V35 not repeat safe");
                } catch(java.sql.SQLException failure) {throw new IllegalStateException("Console upgrade fixture failed",failure);}
            }
            case "upgrade-worker-ip" -> {
                if (flyway.migrate().migrationsExecuted != 3)
                    throw new IllegalStateException("Expected V27 through V29 migrations");
                flyway.validate();
                if (!"29".equals(flyway.info().current().getVersion().getVersion())
                        || flyway.migrate().migrationsExecuted != 0)
                    throw new IllegalStateException("Worker IP migration is not repeat-safe");
            }
            case "upgrade-analysis" -> {
                if (flyway.migrate().migrationsExecuted != 2)
                    throw new IllegalStateException("Expected only V27 and V28 migrations");
                flyway.validate();
                if (!"28".equals(flyway.info().current().getVersion().getVersion())
                        || flyway.migrate().migrationsExecuted != 0)
                    throw new IllegalStateException("Analysis migration upgrade is not repeat-safe");
            }
            case "migrate" -> {
                var result = flyway.migrate();
                if (result.migrationsExecuted != Integer.parseInt(baselineVersion))
                    throw new IllegalStateException("Unexpected migration count");
            }
            case "baseline" -> flyway.baseline();
            case "verify" -> {
                flyway.validate();
                if (flyway.migrate().migrationsExecuted != 0)
                    throw new IllegalStateException("Baseline unexpectedly re-executed migrations");
            }
            default -> throw new IllegalArgumentException("Unknown operation");
        }
    }
}
