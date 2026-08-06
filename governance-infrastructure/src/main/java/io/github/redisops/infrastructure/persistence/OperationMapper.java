package io.github.redisops.infrastructure.persistence;

import io.github.redisops.domain.operation.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface OperationMapper {
    @Select("SELECT id,command_name commandName,command_version commandVersion,category,access_mode accessMode,risk_level riskLevel,enabled,parameter_schema_json parameterSchemaJson,key_position keyPosition,routing_policy routingPolicy,approval_policy approvalPolicy,max_value_bytes maxValueBytes,CAST(allowed_data_types_json AS CHAR) allowedDataTypesJson,missing_key_policy missingKeyPolicy,blocked_by_default blockedByDefault,change_reason changeReason,updated_by updatedBy,version,created_at createdAt,updated_at updatedAt FROM operation_command_definition WHERE (#{includeDisabled}=TRUE OR enabled=TRUE) AND (#{writes}=TRUE OR access_mode='READ') ORDER BY id")
    List<OperationCommand> commands(@Param("writes") boolean writes, @Param("includeDisabled") boolean includeDisabled);
    @Select("SELECT id,command_name commandName,command_version commandVersion,category,access_mode accessMode,risk_level riskLevel,enabled,parameter_schema_json parameterSchemaJson,key_position keyPosition,routing_policy routingPolicy,approval_policy approvalPolicy,max_value_bytes maxValueBytes,CAST(allowed_data_types_json AS CHAR) allowedDataTypesJson,missing_key_policy missingKeyPolicy,blocked_by_default blockedByDefault,change_reason changeReason,updated_by updatedBy,version,created_at createdAt,updated_at updatedAt FROM operation_command_definition WHERE command_name=#{name} AND enabled=TRUE ORDER BY command_version DESC LIMIT 1")
    OperationCommand command(String name);
    @Update("UPDATE operation_command_definition SET enabled=#{enabled},risk_level=#{riskLevel},approval_policy=#{approvalPolicy},max_value_bytes=#{maxValueBytes},allowed_data_types_json=CAST(#{allowedDataTypesJson} AS JSON),missing_key_policy=#{missingKeyPolicy},blocked_by_default=#{blockedByDefault},change_reason=#{changeReason},updated_by=#{updatedBy},version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND version=#{version}")
    int updateCommand(CommandRow row);
    @Insert("INSERT INTO redis_operation(operation_no,cluster_id,database_no,command_name,arguments_json,arguments_digest,access_mode,risk_level,status,preview_json,approval_note,operator_name,version) VALUES(#{operationNo},#{clusterId},#{databaseNo},#{commandName},CAST(#{argumentsJson} AS JSON),#{argumentsDigest},#{accessMode},#{riskLevel},#{status},CAST(#{previewJson} AS JSON),#{approvalNote},#{operatorName},0)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(OperationRow row);
    @Select("SELECT id,operation_no operationNo,cluster_id clusterId,database_no databaseNo,command_name commandName,CAST(arguments_json AS CHAR) argumentsJson,arguments_digest argumentsDigest,access_mode accessMode,risk_level riskLevel,status,CAST(preview_json AS CHAR) previewJson,approval_note approvalNote,operator_name operatorName,approver_name approverName,CAST(result_json AS CHAR) resultJson,version,created_at createdAt,updated_at updatedAt FROM redis_operation WHERE id=#{id}")
    RedisOperation find(long id);
    @Select("SELECT id,operation_no operationNo,cluster_id clusterId,database_no databaseNo,command_name commandName,CAST(arguments_json AS CHAR) argumentsJson,arguments_digest argumentsDigest,access_mode accessMode,risk_level riskLevel,status,CAST(preview_json AS CHAR) previewJson,approval_note approvalNote,operator_name operatorName,approver_name approverName,CAST(result_json AS CHAR) resultJson,version,created_at createdAt,updated_at updatedAt FROM redis_operation ORDER BY id DESC LIMIT #{offset},#{size}")
    List<RedisOperation> list(@Param("offset") int offset, @Param("size") int size);
    @Update("UPDATE redis_operation SET status=#{status},approval_note=#{approvalNote},approver_name=#{approverName},result_json=CAST(#{resultJson} AS JSON),version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND version=#{version}")
    int update(OperationRow row);
    class OperationRow {
        public Long id;
        public long clusterId;
        public int databaseNo;
        public String operationNo, commandName, argumentsJson, argumentsDigest, accessMode, riskLevel, status,
                previewJson, approvalNote, operatorName, approverName, resultJson;
        public long version;
    }
    class CommandRow {
        public long id, version;
        public boolean enabled;
        public String riskLevel, approvalPolicy, allowedDataTypesJson, missingKeyPolicy, changeReason, updatedBy;
        public boolean blockedByDefault;
        public int maxValueBytes;
    }
}
