package io.github.redisops.infrastructure.persistence;

import io.github.redisops.domain.idempotency.IdempotencyRecord;
import org.apache.ibatis.annotations.*;

@Mapper
public interface IdempotencyMapper {
    @Insert("INSERT INTO idempotency_record(operator_id,idempotency_key,operation,request_digest,status,expires_at) VALUES(#{operator},#{key},#{operation},#{digest},'PROCESSING',DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 24 HOUR))")
    int insert(@Param("operator") String operator, @Param("key") String key, @Param("operation") String operation,
            @Param("digest") String digest);
    @Select("SELECT operator_id AS operator,idempotency_key AS `key`,operation,request_digest,status,resource_id FROM idempotency_record WHERE operator_id=#{operator} AND idempotency_key=#{key} AND expires_at > CURRENT_TIMESTAMP(3)")
    IdempotencyRecord find(@Param("operator") String operator, @Param("key") String key);
    @Update("UPDATE idempotency_record SET status='COMPLETED',resource_id=#{resourceId} WHERE operator_id=#{operator} AND idempotency_key=#{key}")
    void complete(@Param("operator") String operator, @Param("key") String key, @Param("resourceId") String resourceId);
    @Update("UPDATE idempotency_record SET status='FAILED' WHERE operator_id=#{operator} AND idempotency_key=#{key}")
    void fail(@Param("operator") String operator, @Param("key") String key);
}
