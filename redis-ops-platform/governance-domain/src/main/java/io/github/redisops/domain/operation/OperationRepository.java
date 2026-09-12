package io.github.redisops.domain.operation;

import java.util.List;
import java.util.Optional;

public interface OperationRepository {
    List<OperationCommand> commands(boolean writes, boolean includeDisabled);
    Optional<OperationCommand> command(String name);
    RedisOperation save(RedisOperation operation);
    Optional<RedisOperation> find(long id);
    List<RedisOperation> list(int page, int size);
    boolean update(RedisOperation operation, long version);
    boolean updateCommand(OperationCommand command, long version);
}
