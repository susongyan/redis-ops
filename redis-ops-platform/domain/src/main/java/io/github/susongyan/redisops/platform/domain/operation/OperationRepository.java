package io.github.susongyan.redisops.platform.domain.operation;

import java.util.List;
import java.util.Optional;

public interface OperationRepository {
    void lockCatalog();
    List<OperationCommand> commands(boolean writes, boolean includeDisabled);
    Optional<OperationCommand> command(String name);
    RedisOperation save(RedisOperation operation);
    Optional<RedisOperation> find(long id);
    Optional<RedisOperation> findByNumber(String number);
    List<RedisOperation> list(int page, int size);
    boolean update(RedisOperation operation, long version);
    boolean updateCommand(OperationCommand command, long version);
    OperationCommand createCommand(OperationCommand command);
}
