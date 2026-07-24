package io.github.redisops.infrastructure.persistence;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("io.github.redisops.infrastructure.persistence")
public class PersistenceConfiguration {
}
