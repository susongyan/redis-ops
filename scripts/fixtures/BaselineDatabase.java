import org.flywaydb.core.Flyway;

/** Isolated database snapshot generation only; credentials come from the child environment. */
public class BaselineDatabase {
    public static void main(String[] args) {
        Flyway flyway = Flyway.configure()
                .dataSource(System.getenv("BASELINE_JDBC_URL"), "root", System.getenv("BASELINE_DB_PASSWORD"))
                .locations("filesystem:" + args[1])
                .baselineVersion("26")
                .load();
        switch (args[0]) {
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
                if (result.migrationsExecuted != 26) throw new IllegalStateException("Expected 26 migrations");
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
