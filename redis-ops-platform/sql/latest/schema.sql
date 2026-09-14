
/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `alert_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `rule_id` bigint NOT NULL,
  `resource_type` varchar(48) COLLATE utf8mb4_unicode_ci NOT NULL,
  `resource_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `severity` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `title` varchar(256) COLLATE utf8mb4_unicode_ci NOT NULL,
  `evidence_json` json DEFAULT NULL,
  `first_seen_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `last_seen_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `acknowledged_at` timestamp(3) NULL DEFAULT NULL,
  `acknowledged_by` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `resolved_at` timestamp(3) NULL DEFAULT NULL,
  `silence_until` timestamp(3) NULL DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_alert_event_dedup` (`rule_id`,`resource_type`,`resource_id`),
  KEY `idx_alert_event_status` (`status`,`severity`,`last_seen_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `alert_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `rule_type` varchar(48) COLLATE utf8mb4_unicode_ci NOT NULL,
  `severity` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  `threshold_value` double DEFAULT NULL,
  `duration_seconds` int NOT NULL DEFAULT '0',
  `channel_id` bigint DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_alert_rule_name` (`name`),
  KEY `idx_alert_rule_enabled` (`enabled`,`rule_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `analysis_agent_profile` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `protocol` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `endpoint` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  `supported_types_json` json NOT NULL,
  `timeout_ms` int NOT NULL DEFAULT '5000',
  `priority` int NOT NULL DEFAULT '100',
  `version` bigint NOT NULL DEFAULT '0',
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_analysis_agent_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `analysis_run` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `request_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `analysis_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `resource_type` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `resource_id` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `protocol` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `provider` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `summary` varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `confidence` decimal(5,4) NOT NULL,
  `evidence_json` json NOT NULL,
  `recommendations_json` json NOT NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_analysis_run_request` (`request_id`),
  KEY `idx_analysis_run_resource` (`resource_type`,`resource_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `app_cluster_binding` (
  `application_id` bigint NOT NULL,
  `cluster_id` bigint NOT NULL,
  `client_type` varchar(64) DEFAULT NULL,
  `client_version` varchar(64) DEFAULT NULL,
  `pool_config_json` json DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`application_id`,`cluster_id`),
  KEY `idx_binding_cluster` (`cluster_id`),
  CONSTRAINT `fk_binding_app` FOREIGN KEY (`application_id`) REFERENCES `application` (`id`),
  CONSTRAINT `fk_binding_cluster` FOREIGN KEY (`cluster_id`) REFERENCES `redis_cluster` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `application` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `app_code` varchar(128) NOT NULL,
  `name` varchar(128) NOT NULL,
  `owner` varchar(128) DEFAULT NULL,
  `business_line` varchar(128) DEFAULT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'ACTIVE',
  `version` bigint NOT NULL DEFAULT '0',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `deleted_at` datetime(3) DEFAULT NULL,
  `active_code` varchar(128) GENERATED ALWAYS AS (if((`deleted_at` is null),`app_code`,NULL)) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_application_code_active` (`active_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `async_job` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `job_type` varchar(64) NOT NULL,
  `biz_id` bigint NOT NULL,
  `payload_json` json NOT NULL,
  `status` varchar(32) NOT NULL,
  `idempotency_key` varchar(160) NOT NULL,
  `lease_owner` varchar(160) DEFAULT NULL,
  `lease_until` datetime(3) DEFAULT NULL,
  `attempts` int NOT NULL DEFAULT '0',
  `max_attempts` int NOT NULL DEFAULT '3',
  `next_run_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `last_error` varchar(1024) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_async_job_idempotency` (`idempotency_key`),
  KEY `idx_async_job_poll` (`status`,`next_run_at`,`lease_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `audit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `operator_id` varchar(128) NOT NULL,
  `action` varchar(64) NOT NULL,
  `resource_type` varchar(64) NOT NULL,
  `resource_id` varchar(128) NOT NULL,
  `result` varchar(32) NOT NULL,
  `request_id` varchar(64) DEFAULT NULL,
  `request_digest` varchar(128) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_audit_resource` (`resource_type`,`resource_id`,`created_at`),
  KEY `idx_audit_operator` (`operator_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cleanup_governance_checkpoint` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `run_id` bigint NOT NULL,
  `shard_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `cursor_value` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '0',
  `scanned_keys` bigint NOT NULL DEFAULT '0',
  `status` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cleanup_governance_checkpoint` (`run_id`,`shard_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cleanup_governance_run` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL,
  `run_no` varchar(48) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `planned_keys` bigint NOT NULL DEFAULT '0',
  `scanned_keys` bigint NOT NULL DEFAULT '0',
  `candidate_keys` bigint NOT NULL DEFAULT '0',
  `deleted_keys` bigint NOT NULL DEFAULT '0',
  `skipped_keys` bigint NOT NULL DEFAULT '0',
  `failed_keys` bigint NOT NULL DEFAULT '0',
  `started_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `completed_at` timestamp(3) NULL DEFAULT NULL,
  `error_code` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cleanup_governance_run_no` (`run_no`),
  KEY `idx_cleanup_governance_run_task` (`task_id`,`started_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cleanup_governance_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_no` varchar(48) COLLATE utf8mb4_unicode_ci NOT NULL,
  `cluster_id` bigint NOT NULL,
  `database_no` int NOT NULL DEFAULT '0',
  `include_pattern` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '*',
  `impact_limit` bigint NOT NULL,
  `scan_rate_per_second` int NOT NULL DEFAULT '200',
  `status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `approval_status` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `approval_note` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cleanup_governance_task_no` (`task_no`),
  KEY `idx_cleanup_governance_cluster` (`cluster_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cluster_relation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(128) NOT NULL,
  `relation_type` varchar(32) NOT NULL,
  `primary_cluster_id` bigint NOT NULL,
  `standby_cluster_id` bigint NOT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'ACTIVE',
  `desired_rpo_seconds` bigint NOT NULL,
  `description` varchar(512) DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `deleted_at` datetime(3) DEFAULT NULL,
  `active_pair` varchar(80) GENERATED ALWAYS AS (if((`deleted_at` is null),concat(least(`primary_cluster_id`,`standby_cluster_id`),_utf8mb4':',greatest(`primary_cluster_id`,`standby_cluster_id`)),NULL)) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cluster_relation_pair_active` (`active_pair`),
  KEY `idx_relation_primary` (`primary_cluster_id`),
  KEY `idx_relation_standby` (`standby_cluster_id`),
  CONSTRAINT `fk_relation_primary` FOREIGN KEY (`primary_cluster_id`) REFERENCES `redis_cluster` (`id`),
  CONSTRAINT `fk_relation_standby` FOREIGN KEY (`standby_cluster_id`) REFERENCES `redis_cluster` (`id`),
  CONSTRAINT `chk_relation_clusters` CHECK ((`primary_cluster_id` <> `standby_cluster_id`)),
  CONSTRAINT `chk_relation_rpo` CHECK ((`desired_rpo_seconds` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `collector_run` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `cluster_id` bigint NOT NULL,
  `collection_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `started_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `completed_at` timestamp(3) NULL DEFAULT NULL,
  `summary_json` json DEFAULT NULL,
  `error_code` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `error_message` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_collector_run_cluster` (`cluster_id`,`collection_type`,`created_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `discovery_run` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `cluster_id` bigint NOT NULL,
  `status` varchar(32) NOT NULL,
  `started_at` datetime(3) NOT NULL,
  `finished_at` datetime(3) DEFAULT NULL,
  `node_count` int DEFAULT NULL,
  `error_message` varchar(1024) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_discovery_cluster_time` (`cluster_id`,`started_at`),
  CONSTRAINT `fk_discovery_cluster` FOREIGN KEY (`cluster_id`) REFERENCES `redis_cluster` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `idc` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `code` varchar(64) NOT NULL,
  `name` varchar(128) NOT NULL,
  `region_id` bigint NOT NULL,
  `network_domain` varchar(128) DEFAULT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'ACTIVE',
  `description` varchar(512) DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `deleted_at` datetime(3) DEFAULT NULL,
  `active_region_code` varchar(160) GENERATED ALWAYS AS (if((`deleted_at` is null),concat(`region_id`,_utf8mb4':',`code`),NULL)) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idc_region_code_active` (`active_region_code`),
  KEY `idx_idc_region` (`region_id`),
  CONSTRAINT `fk_idc_region` FOREIGN KEY (`region_id`) REFERENCES `region` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `idempotency_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `operator_id` varchar(128) NOT NULL,
  `idempotency_key` varchar(128) NOT NULL,
  `operation` varchar(128) NOT NULL,
  `request_digest` varchar(128) NOT NULL,
  `status` varchar(32) NOT NULL,
  `resource_id` varchar(128) DEFAULT NULL,
  `response_json` json DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `expires_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idempotency_operator_key` (`operator_id`,`idempotency_key`),
  KEY `idx_idempotency_expiry` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `notification_channel` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `channel_uuid` char(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `channel_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `encrypted_config` blob NOT NULL,
  `key_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notification_channel_uuid` (`channel_uuid`),
  UNIQUE KEY `uk_notification_channel_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `notification_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` bigint NOT NULL,
  `channel_id` bigint NOT NULL,
  `status` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `attempt` int NOT NULL DEFAULT '0',
  `next_attempt_at` timestamp(3) NULL DEFAULT NULL,
  `last_error` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `delivered_at` timestamp(3) NULL DEFAULT NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_notification_record_delivery` (`status`,`next_attempt_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `operation_command_definition` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `command_name` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `command_version` int NOT NULL DEFAULT '1',
  `category` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `access_mode` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `risk_level` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  `parameter_schema_json` json NOT NULL,
  `key_position` int NOT NULL DEFAULT '1',
  `routing_policy` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'SINGLE_KEY',
  `approval_policy` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'CONFIRM',
  `max_value_bytes` int NOT NULL DEFAULT '4096',
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `version` bigint NOT NULL DEFAULT '0',
  `allowed_data_types_json` json NOT NULL,
  `missing_key_policy` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'EXISTING_REQUIRED',
  `blocked_by_default` tinyint(1) NOT NULL DEFAULT '0',
  `change_reason` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `updated_by` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_operation_command` (`command_name`,`command_version`)
) ENGINE=InnoDB AUTO_INCREMENT=16 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `redis_cluster` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(128) NOT NULL,
  `environment` varchar(32) NOT NULL,
  `business_line` varchar(128) DEFAULT NULL,
  `owner` varchar(128) NOT NULL,
  `ops_owner` varchar(128) DEFAULT NULL,
  `service_level` varchar(32) DEFAULT NULL,
  `mode` varchar(32) NOT NULL,
  `redis_version` varchar(32) DEFAULT NULL,
  `endpoint` varchar(512) NOT NULL,
  `idc_id` bigint DEFAULT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'ACTIVE',
  `version` bigint NOT NULL DEFAULT '0',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `deleted_at` datetime(3) DEFAULT NULL,
  `active_name` varchar(128) GENERATED ALWAYS AS (if((`deleted_at` is null),`name`,NULL)) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cluster_name_active` (`active_name`),
  KEY `idx_cluster_filter` (`environment`,`business_line`,`owner`,`status`),
  KEY `idx_cluster_idc` (`idc_id`),
  CONSTRAINT `fk_cluster_idc` FOREIGN KEY (`idc_id`) REFERENCES `idc` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `redis_cluster_secret` (
  `cluster_id` bigint NOT NULL,
  `secret_uuid` char(36) NOT NULL,
  `encrypted_secret` blob NOT NULL,
  `key_id` varchar(64) NOT NULL,
  `username` varchar(128) DEFAULT NULL,
  `secret_status` varchar(32) NOT NULL DEFAULT 'ENCRYPTED',
  `version` bigint NOT NULL DEFAULT '0',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`cluster_id`),
  UNIQUE KEY `uk_cluster_secret_uuid` (`secret_uuid`),
  KEY `idx_cluster_secret_rotation` (`secret_status`,`key_id`),
  CONSTRAINT `fk_cluster_secret_cluster` FOREIGN KEY (`cluster_id`) REFERENCES `redis_cluster` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `redis_node` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `cluster_id` bigint NOT NULL,
  `host` varchar(255) NOT NULL,
  `port` int NOT NULL,
  `node_id` varchar(128) DEFAULT NULL,
  `role` varchar(32) NOT NULL,
  `master_node_id` varchar(128) DEFAULT NULL,
  `slot_ranges_json` json DEFAULT NULL,
  `memory_bytes` bigint DEFAULT NULL,
  `status` varchar(32) NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_node_endpoint` (`cluster_id`,`host`,`port`),
  CONSTRAINT `fk_node_cluster` FOREIGN KEY (`cluster_id`) REFERENCES `redis_cluster` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `redis_operation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `operation_no` varchar(48) COLLATE utf8mb4_unicode_ci NOT NULL,
  `cluster_id` bigint NOT NULL,
  `database_no` int NOT NULL DEFAULT '0',
  `command_name` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `arguments_json` json NOT NULL,
  `arguments_digest` char(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `access_mode` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `risk_level` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `preview_json` json DEFAULT NULL,
  `approval_note` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `operator_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `approver_name` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `result_json` json DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_redis_operation_no` (`operation_no`),
  KEY `idx_redis_operation_history` (`cluster_id`,`created_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `region` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `code` varchar(64) NOT NULL,
  `name` varchar(128) NOT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'ACTIVE',
  `description` varchar(512) DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `deleted_at` datetime(3) DEFAULT NULL,
  `active_code` varchar(64) GENERATED ALWAYS AS (if((`deleted_at` is null),`code`,NULL)) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_region_code_active` (`active_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `risk_finding` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `run_id` bigint NOT NULL,
  `risk_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `risk_level` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `key_name` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `key_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `redis_type` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `memory_bytes` bigint DEFAULT NULL,
  `element_count` bigint DEFAULT NULL,
  `ttl_seconds` bigint DEFAULT NULL,
  `node_id` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_risk_finding_run` (`run_id`,`risk_type`,`risk_level`),
  KEY `idx_risk_finding_key_name` (`key_name`(128))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `scan_run` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL,
  `run_no` varchar(48) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `planned_keys` bigint NOT NULL DEFAULT '0',
  `scanned_keys` bigint NOT NULL DEFAULT '0',
  `finding_count` bigint NOT NULL DEFAULT '0',
  `started_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `completed_at` timestamp(3) NULL DEFAULT NULL,
  `error_code` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scan_run_no` (`run_no`),
  KEY `idx_scan_run_task` (`task_id`,`started_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `scan_shard_checkpoint` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `run_id` bigint NOT NULL,
  `shard_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `cursor_value` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '0',
  `scanned_keys` bigint NOT NULL DEFAULT '0',
  `status` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scan_shard` (`run_id`,`shard_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `scan_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_no` varchar(48) COLLATE utf8mb4_unicode_ci NOT NULL,
  `cluster_id` bigint NOT NULL,
  `database_no` int NOT NULL DEFAULT '0',
  `include_pattern` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '*',
  `check_large_key` tinyint(1) NOT NULL DEFAULT '1',
  `check_no_ttl` tinyint(1) NOT NULL DEFAULT '1',
  `large_key_threshold_bytes` bigint NOT NULL,
  `scan_rate_per_second` int NOT NULL,
  `max_findings` int NOT NULL,
  `status` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `deleted_at` timestamp(3) NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scan_task_no` (`task_no`),
  KEY `idx_scan_task_cluster` (`cluster_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `switchover` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `relation_id` bigint NOT NULL,
  `old_primary_cluster_id` bigint NOT NULL,
  `old_standby_cluster_id` bigint NOT NULL,
  `stopped_task_id` bigint NOT NULL,
  `reverse_task_id` bigint DEFAULT NULL,
  `status` varchar(32) NOT NULL,
  `operator_id` varchar(128) NOT NULL,
  `source_write_fenced` tinyint(1) NOT NULL DEFAULT '0',
  `source_fence_note` varchar(512) DEFAULT NULL,
  `last_error` varchar(1024) DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `confirmed_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_switchover_relation` (`relation_id`,`created_at`),
  KEY `fk_switchover_old_primary` (`old_primary_cluster_id`),
  KEY `fk_switchover_old_standby` (`old_standby_cluster_id`),
  KEY `fk_switchover_stopped_task` (`stopped_task_id`),
  KEY `fk_switchover_reverse_task` (`reverse_task_id`),
  CONSTRAINT `fk_switchover_old_primary` FOREIGN KEY (`old_primary_cluster_id`) REFERENCES `redis_cluster` (`id`),
  CONSTRAINT `fk_switchover_old_standby` FOREIGN KEY (`old_standby_cluster_id`) REFERENCES `redis_cluster` (`id`),
  CONSTRAINT `fk_switchover_relation` FOREIGN KEY (`relation_id`) REFERENCES `cluster_relation` (`id`),
  CONSTRAINT `fk_switchover_reverse_task` FOREIGN KEY (`reverse_task_id`) REFERENCES `sync_task` (`id`),
  CONSTRAINT `fk_switchover_stopped_task` FOREIGN KEY (`stopped_task_id`) REFERENCES `sync_task` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sync_channel_checkpoint` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL,
  `channel_id` varchar(160) NOT NULL,
  `source_node_id` varchar(160) DEFAULT NULL,
  `slot_ranges` varchar(1024) DEFAULT NULL,
  `replication_id` varchar(80) DEFAULT NULL,
  `received_offset` bigint NOT NULL DEFAULT '-1',
  `applied_offset` bigint NOT NULL DEFAULT '-1',
  `status` varchar(32) NOT NULL,
  `last_heartbeat_at` datetime(3) DEFAULT NULL,
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sync_channel` (`task_id`,`channel_id`),
  KEY `idx_sync_channel_status` (`task_id`,`status`),
  CONSTRAINT `fk_sync_channel_task` FOREIGN KEY (`task_id`) REFERENCES `sync_task` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sync_full_progress` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL,
  `full_sync_epoch` varchar(64) NOT NULL,
  `channel_id` varchar(128) NOT NULL,
  `lane` int NOT NULL DEFAULT '-1',
  `stage` varchar(32) NOT NULL,
  `total_bytes` bigint DEFAULT NULL,
  `received_bytes` bigint NOT NULL DEFAULT '0',
  `parsed_bytes` bigint NOT NULL DEFAULT '0',
  `total_keys` bigint DEFAULT NULL,
  `parsed_keys` bigint NOT NULL DEFAULT '0',
  `applied_keys` bigint NOT NULL DEFAULT '0',
  `applied_bytes` bigint NOT NULL DEFAULT '0',
  `status` varchar(32) NOT NULL,
  `started_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sync_full_progress_channel_lane` (`task_id`,`full_sync_epoch`,`channel_id`,`lane`),
  KEY `idx_sync_full_progress_task` (`task_id`),
  CONSTRAINT `fk_sync_full_progress_task` FOREIGN KEY (`task_id`) REFERENCES `sync_task` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sync_metric_snapshot` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL,
  `channel_id` varchar(160) DEFAULT NULL,
  `timestamp_lag_seconds` bigint DEFAULT NULL,
  `estimated_lag_seconds` bigint DEFAULT NULL,
  `offset_gap_bytes` bigint NOT NULL DEFAULT '0',
  `backlog_bytes` bigint NOT NULL DEFAULT '0',
  `source_bytes_per_second` bigint NOT NULL DEFAULT '0',
  `target_apply_bytes_per_second` bigint NOT NULL DEFAULT '0',
  `catch_up_eta_seconds` bigint DEFAULT NULL,
  `calculation_method` varchar(32) NOT NULL,
  `confidence` varchar(16) NOT NULL,
  `collected_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_sync_metric_task_time` (`task_id`,`collected_at`),
  CONSTRAINT `fk_sync_metric_task` FOREIGN KEY (`task_id`) REFERENCES `sync_task` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sync_precheck_report` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL,
  `status` varchar(32) NOT NULL,
  `report_json` json NOT NULL,
  `checked_at` datetime(3) NOT NULL,
  `valid_until` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_sync_precheck_latest` (`task_id`,`checked_at`),
  CONSTRAINT `fk_sync_precheck_task` FOREIGN KEY (`task_id`) REFERENCES `sync_task` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sync_runtime` (
  `task_id` bigint NOT NULL,
  `runtime_id` varchar(36) NOT NULL,
  `lease_owner` varchar(160) DEFAULT NULL,
  `lease_until` datetime(3) DEFAULT NULL,
  `fencing_generation` bigint NOT NULL DEFAULT '0',
  `phase` varchar(32) NOT NULL,
  `heartbeat_at` datetime(3) DEFAULT NULL,
  `spool_bytes` bigint NOT NULL DEFAULT '0',
  `target_fence_generation` bigint DEFAULT NULL,
  `fence_published_at` datetime(3) DEFAULT NULL,
  `takeover_count` int NOT NULL DEFAULT '0',
  `recovery_action` varchar(64) DEFAULT NULL,
  `last_error` varchar(1024) DEFAULT NULL,
  `started_at` datetime(3) DEFAULT NULL,
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `worker_ip` varchar(255) DEFAULT NULL COMMENT 'IP reported by the runtime lease owner',
  `worker_ip_runtime_id` varchar(128) DEFAULT NULL COMMENT 'Runtime that reported the IP; guards mixed-version takeover',
  PRIMARY KEY (`task_id`),
  UNIQUE KEY `uk_sync_runtime_id` (`runtime_id`),
  KEY `idx_sync_runtime_lease` (`lease_until`),
  CONSTRAINT `fk_sync_runtime_task` FOREIGN KEY (`task_id`) REFERENCES `sync_task` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sync_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_no` varchar(64) NOT NULL,
  `relation_id` bigint DEFAULT NULL,
  `source_cluster_id` bigint NOT NULL,
  `target_cluster_id` bigint NOT NULL,
  `purpose` varchar(32) NOT NULL,
  `sync_mode` varchar(32) NOT NULL,
  `status` varchar(32) NOT NULL,
  `tool_type` varchar(64) DEFAULT NULL,
  `source_db` int NOT NULL DEFAULT '0',
  `target_db` int NOT NULL DEFAULT '0',
  `include_patterns_json` json NOT NULL,
  `exclude_patterns_json` json NOT NULL,
  `command_policy_json` json NOT NULL,
  `rate_limit_ops` bigint NOT NULL DEFAULT '50000',
  `bandwidth_limit_bytes_per_second` bigint NOT NULL DEFAULT '104857600',
  `spool_limit_bytes` bigint NOT NULL DEFAULT '53687091200',
  `full_apply_concurrency` int NOT NULL DEFAULT '4',
  `full_apply_pipeline_size` int NOT NULL DEFAULT '100',
  `desired_action` varchar(32) DEFAULT NULL,
  `write_fenced` tinyint(1) NOT NULL DEFAULT '0',
  `write_fence_note` varchar(512) DEFAULT NULL,
  `blocked_reason` varchar(128) DEFAULT NULL,
  `full_sync_epoch` varchar(36) DEFAULT NULL,
  `last_rpo_seconds` bigint DEFAULT NULL,
  `last_error` varchar(1024) DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `finished_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sync_task_no` (`task_no`),
  KEY `idx_sync_task_relation` (`relation_id`,`created_at`),
  KEY `idx_sync_task_status` (`status`),
  KEY `fk_sync_task_source` (`source_cluster_id`),
  KEY `fk_sync_task_target` (`target_cluster_id`),
  CONSTRAINT `fk_sync_task_relation` FOREIGN KEY (`relation_id`) REFERENCES `cluster_relation` (`id`),
  CONSTRAINT `fk_sync_task_source` FOREIGN KEY (`source_cluster_id`) REFERENCES `redis_cluster` (`id`),
  CONSTRAINT `fk_sync_task_target` FOREIGN KEY (`target_cluster_id`) REFERENCES `redis_cluster` (`id`),
  CONSTRAINT `chk_sync_task_clusters` CHECK ((`source_cluster_id` <> `target_cluster_id`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sync_task_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL,
  `from_status` varchar(32) DEFAULT NULL,
  `to_status` varchar(32) NOT NULL,
  `operator_id` varchar(128) NOT NULL,
  `message` varchar(1024) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_sync_event_task` (`task_id`,`created_at`),
  CONSTRAINT `fk_sync_event_task` FOREIGN KEY (`task_id`) REFERENCES `sync_task` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ttl_governance_checkpoint` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `run_id` bigint NOT NULL,
  `shard_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `cursor_value` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '0',
  `scanned_keys` bigint NOT NULL DEFAULT '0',
  `status` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ttl_governance_checkpoint` (`run_id`,`shard_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ttl_governance_run` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL,
  `run_no` varchar(48) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `planned_keys` bigint NOT NULL DEFAULT '0',
  `scanned_keys` bigint NOT NULL DEFAULT '0',
  `candidate_keys` bigint NOT NULL DEFAULT '0',
  `applied_keys` bigint NOT NULL DEFAULT '0',
  `skipped_keys` bigint NOT NULL DEFAULT '0',
  `failed_keys` bigint NOT NULL DEFAULT '0',
  `started_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `completed_at` timestamp(3) NULL DEFAULT NULL,
  `error_code` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ttl_governance_run_no` (`run_no`),
  KEY `idx_ttl_governance_run_task` (`task_id`,`started_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ttl_governance_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_no` varchar(48) COLLATE utf8mb4_unicode_ci NOT NULL,
  `cluster_id` bigint NOT NULL,
  `database_no` int NOT NULL DEFAULT '0',
  `include_pattern` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '*',
  `target_ttl_seconds` bigint NOT NULL,
  `scan_rate_per_second` int NOT NULL DEFAULT '500',
  `max_keys` bigint NOT NULL DEFAULT '100000',
  `status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `approval_status` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `version` bigint NOT NULL DEFAULT '0',
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ttl_governance_task_no` (`task_no`),
  KEY `idx_ttl_governance_cluster` (`cluster_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `validation_difference` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `run_id` bigint NOT NULL,
  `difference_type` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `key_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `key_name` text COLLATE utf8mb4_unicode_ci,
  `redis_type` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_size` bigint DEFAULT NULL,
  `target_size` bigint DEFAULT NULL,
  `source_ttl_seconds` bigint DEFAULT NULL,
  `target_ttl_seconds` bigint DEFAULT NULL,
  `comparison_level` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `degraded_reason` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_validation_difference_run` (`run_id`,`id` DESC),
  KEY `idx_validation_difference_type` (`run_id`,`difference_type`),
  CONSTRAINT `fk_validation_difference_run` FOREIGN KEY (`run_id`) REFERENCES `validation_run` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `validation_run` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL,
  `run_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `planned_keys` bigint NOT NULL DEFAULT '0',
  `scanned_keys` bigint NOT NULL DEFAULT '0',
  `compared_keys` bigint NOT NULL DEFAULT '0',
  `difference_count` bigint NOT NULL DEFAULT '0',
  `degraded_count` bigint NOT NULL DEFAULT '0',
  `unverifiable_count` bigint NOT NULL DEFAULT '0',
  `inconclusive_count` bigint NOT NULL DEFAULT '0',
  `started_at` timestamp(3) NOT NULL,
  `finished_at` timestamp(3) NULL DEFAULT NULL,
  `summary_json` json DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_validation_run_no` (`run_no`),
  KEY `idx_validation_run_task` (`task_id`,`id` DESC),
  CONSTRAINT `fk_validation_run_task` FOREIGN KEY (`task_id`) REFERENCES `validation_task` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `validation_shard_checkpoint` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `run_id` bigint NOT NULL,
  `direction` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `shard_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `scan_cursor` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `scanned_keys` bigint NOT NULL DEFAULT '0',
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_validation_shard_checkpoint` (`run_id`,`direction`,`shard_id`),
  CONSTRAINT `fk_validation_checkpoint_run` FOREIGN KEY (`run_id`) REFERENCES `validation_run` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `validation_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `sync_task_id` bigint DEFAULT NULL,
  `source_cluster_id` bigint NOT NULL,
  `target_cluster_id` bigint NOT NULL,
  `source_db` int NOT NULL,
  `target_db` int NOT NULL,
  `strictness` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `include_patterns_json` json NOT NULL,
  `exclude_patterns_json` json NOT NULL,
  `sample_seed` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `sampling_mode` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'COUNT',
  `sample_limit` int NOT NULL,
  `sample_percentage` decimal(5,2) DEFAULT NULL,
  `ttl_tolerance_seconds` bigint NOT NULL,
  `large_key_threshold_bytes` bigint NOT NULL,
  `max_deep_compare_bytes` bigint NOT NULL,
  `chunk_bytes` int NOT NULL,
  `max_elements_per_key` int NOT NULL,
  `status` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `last_error` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_validation_task_no` (`task_no`),
  KEY `idx_validation_task_status` (`status`),
  KEY `fk_validation_source_cluster` (`source_cluster_id`),
  KEY `fk_validation_target_cluster` (`target_cluster_id`),
  KEY `idx_validation_task_sync` (`sync_task_id`,`id` DESC),
  CONSTRAINT `fk_validation_source_cluster` FOREIGN KEY (`source_cluster_id`) REFERENCES `redis_cluster` (`id`),
  CONSTRAINT `fk_validation_sync_task` FOREIGN KEY (`sync_task_id`) REFERENCES `sync_task` (`id`),
  CONSTRAINT `fk_validation_target_cluster` FOREIGN KEY (`target_cluster_id`) REFERENCES `redis_cluster` (`id`),
  CONSTRAINT `chk_validation_clusters` CHECK ((`source_cluster_id` <> `target_cluster_id`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;
