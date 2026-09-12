package io.github.redisops.platform.infrastructure.persistence;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("io.github.redisops.platform.infrastructure.persistence")
public class PersistenceConfiguration {
}
