package io.github.susongyan.redisops.platform.infrastructure.persistence;

import io.github.susongyan.redisops.platform.domain.operation.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface OperationMapper {
    @Select("SELECT id,command_name commandName,command_version commandVersion,category,access_mode accessMode,risk_level riskLevel,enabled,parameter_schema_json parameterSchemaJson,key_position keyPosition,routing_policy routingPolicy,approval_policy approvalPolicy,max_value_bytes maxValueBytes,CAST(allowed_data_types_json AS CHAR) allowedDataTypesJson,missing_key_policy missingKeyPolicy,blocked_by_default blockedByDefault,change_reason changeReason,updated_by updatedBy,version,created_at createdAt,updated_at updatedAt,CAST(updated_by_snapshot AS CHAR) updatedBySnapshot FROM operation_command_definition WHERE (#{includeDisabled}=TRUE OR enabled=TRUE) AND (#{writes}=TRUE OR access_mode='READ') ORDER BY id")
    List<OperationCommand> commands(@Param("writes") boolean writes, @Param("includeDisabled") boolean includeDisabled);
    @Select("SELECT id,command_name commandName,command_version commandVersion,category,access_mode accessMode,risk_level riskLevel,enabled,parameter_schema_json parameterSchemaJson,key_position keyPosition,routing_policy routingPolicy,approval_policy approvalPolicy,max_value_bytes maxValueBytes,CAST(allowed_data_types_json AS CHAR) allowedDataTypesJson,missing_key_policy missingKeyPolicy,blocked_by_default blockedByDefault,change_reason changeReason,updated_by updatedBy,version,created_at createdAt,updated_at updatedAt,CAST(updated_by_snapshot AS CHAR) updatedBySnapshot FROM operation_command_definition WHERE command_name=#{name} ORDER BY command_version DESC LIMIT 1")
    OperationCommand command(String name);
    @Update("UPDATE operation_command_definition SET category=#{category},access_mode=#{accessMode},parameter_schema_json=CAST(#{parameterSchemaJson} AS JSON),key_position=#{keyPosition},routing_policy=#{routingPolicy},enabled=#{enabled},risk_level=#{riskLevel},approval_policy=#{approvalPolicy},max_value_bytes=#{maxValueBytes},allowed_data_types_json=CAST(#{allowedDataTypesJson} AS JSON),missing_key_policy=#{missingKeyPolicy},blocked_by_default=#{blockedByDefault},change_reason=#{changeReason},updated_by=#{updatedBy},updated_by_snapshot=#{updatedBySnapshot},version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND version=#{version}")
    int updateCommand(CommandRow row);
    @Insert("INSERT INTO operation_command_definition(command_name,category,access_mode,risk_level,enabled,parameter_schema_json,key_position,routing_policy,approval_policy,max_value_bytes,allowed_data_types_json,missing_key_policy,blocked_by_default,change_reason,updated_by,updated_by_snapshot) VALUES(#{commandName},#{category},#{accessMode},#{riskLevel},#{enabled},CAST(#{parameterSchemaJson} AS JSON),#{keyPosition},#{routingPolicy},#{approvalPolicy},#{maxValueBytes},CAST(#{allowedDataTypesJson} AS JSON),#{missingKeyPolicy},#{blockedByDefault},#{changeReason},#{updatedBy},#{updatedBySnapshot})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertCommand(CommandRow row);
    @Insert("INSERT INTO redis_operation(operation_no,cluster_id,database_no,command_name,arguments_json,arguments_digest,access_mode,risk_level,status,preview_json,approval_note,operator_name,version,operator_snapshot) VALUES(#{operationNo},#{clusterId},#{databaseNo},#{commandName},CAST(#{argumentsJson} AS JSON),#{argumentsDigest},#{accessMode},#{riskLevel},#{status},CAST(#{previewJson} AS JSON),#{approvalNote},#{operatorName},0,#{operatorSnapshot})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(OperationRow row);
    @Select("SELECT id,operation_no operationNo,cluster_id clusterId,database_no databaseNo,command_name commandName,CAST(arguments_json AS CHAR) argumentsJson,arguments_digest argumentsDigest,access_mode accessMode,risk_level riskLevel,status,CAST(preview_json AS CHAR) previewJson,approval_note approvalNote,operator_name operatorName,approver_name approverName,CAST(result_json AS CHAR) resultJson,version,created_at createdAt,updated_at updatedAt,CAST(operator_snapshot AS CHAR) operatorSnapshot,CAST(approver_snapshot AS CHAR) approverSnapshot,executor_name executorName,CAST(executor_snapshot AS CHAR) executorSnapshot FROM redis_operation WHERE id=#{id}")
    RedisOperation find(long id);
    @Select("SELECT id,operation_no operationNo,cluster_id clusterId,database_no databaseNo,command_name commandName,CAST(arguments_json AS CHAR) argumentsJson,arguments_digest argumentsDigest,access_mode accessMode,risk_level riskLevel,status,CAST(preview_json AS CHAR) previewJson,approval_note approvalNote,operator_name operatorName,approver_name approverName,CAST(result_json AS CHAR) resultJson,version,created_at createdAt,updated_at updatedAt,CAST(operator_snapshot AS CHAR) operatorSnapshot,CAST(approver_snapshot AS CHAR) approverSnapshot,executor_name executorName,CAST(executor_snapshot AS CHAR) executorSnapshot FROM redis_operation ORDER BY id DESC LIMIT #{offset},#{size}")
    List<RedisOperation> list(@Param("offset") int offset, @Param("size") int size);
    @Update("UPDATE redis_operation SET status=#{status},approval_note=#{approvalNote},approver_name=#{approverName},approver_snapshot=#{approverSnapshot},executor_name=COALESCE(#{executorName},executor_name),executor_snapshot=COALESCE(#{executorSnapshot},executor_snapshot),result_json=CAST(#{resultJson} AS JSON),version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND version=#{version}")
    int update(OperationRow row);
    class OperationRow {
        public Long id;
        public long clusterId;
        public int databaseNo;
        public String operationNo, commandName, argumentsJson, argumentsDigest, accessMode, riskLevel, status,
                previewJson, approvalNote, operatorName, approverName, resultJson, operatorSnapshot, approverSnapshot,
                executorName, executorSnapshot;
        public long version;
    }
    class CommandRow {
        public String commandName, category, accessMode, parameterSchemaJson, routingPolicy;
        public int keyPosition;
        public long id, version;
        public boolean enabled;
        public String riskLevel, approvalPolicy, allowedDataTypesJson, missingKeyPolicy, changeReason, updatedBy,
                updatedBySnapshot;
        public boolean blockedByDefault;
        public int maxValueBytes;
    }
}
