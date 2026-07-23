package io.github.redisops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GovernanceApplication {
    public static void main(String[] args) { SpringApplication.run(GovernanceApplication.class, args); }
}
