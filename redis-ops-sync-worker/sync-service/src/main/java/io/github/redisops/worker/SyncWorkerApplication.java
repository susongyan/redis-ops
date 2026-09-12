package io.github.redisops.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "io.github.redisops.worker")
@EnableScheduling
public class SyncWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(SyncWorkerApplication.class, args);
    }
}
