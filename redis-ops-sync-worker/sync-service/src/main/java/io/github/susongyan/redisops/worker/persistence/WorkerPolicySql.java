package io.github.susongyan.redisops.worker.persistence;

/** SQL admission gate for this worker release; always use the capability_task alias. */
final class WorkerPolicySql {
    static final String VERSION = "JSON_EXTRACT(capability_task.command_policy_json,'$.policyVersion')";
    static final String SUPPORTED = "JSON_TYPE(capability_task.command_policy_json)='OBJECT' AND BINARY (CASE "
            + "WHEN " + VERSION + " IS NULL OR JSON_TYPE(" + VERSION + ")='NULL' THEN 'v1' "
            + "WHEN JSON_TYPE(" + VERSION + ")='STRING' THEN COALESCE(NULLIF(JSON_UNQUOTE(" + VERSION
            + "),''),'v1') ELSE '' END) IN ('v1','v2','v3')";

    private WorkerPolicySql() {
    }
}
