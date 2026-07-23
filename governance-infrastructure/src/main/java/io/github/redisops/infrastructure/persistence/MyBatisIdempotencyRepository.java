package io.github.redisops.infrastructure.persistence;

import io.github.redisops.domain.idempotency.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class MyBatisIdempotencyRepository implements IdempotencyRepository {
    private final IdempotencyMapper mapper;public MyBatisIdempotencyRepository(IdempotencyMapper mapper){this.mapper=mapper;}
    @Override public boolean tryStart(String operator,String key,String operation,String digest){try{return mapper.insert(operator,key,operation,digest)==1;}catch(DuplicateKeyException e){return false;}}
    @Override public Optional<IdempotencyRecord> find(String operator,String key){return Optional.ofNullable(mapper.find(operator,key));}
    @Override public void complete(String operator,String key,String resourceId){mapper.complete(operator,key,resourceId);}
    @Override public void fail(String operator,String key){mapper.fail(operator,key);}
}
