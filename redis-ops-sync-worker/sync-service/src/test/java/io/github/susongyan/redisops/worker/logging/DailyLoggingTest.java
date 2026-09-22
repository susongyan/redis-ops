package io.github.susongyan.redisops.worker.logging;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.mock.env.MockEnvironment;

class DailyLoggingTest {
    @TempDir
    Path directory;

    @Test
    void activeLogMovesToNextDateWithoutRestart() throws Exception {
        var environment = new MockEnvironment()
                .withProperty("log-dir", directory.toString())
                .withProperty("spring.application.name", "logging-test");
        // Isolate the test from the JVM-wide logging context.
        var constructor = Class.forName("org.springframework.boot.logging.logback.SpringBootJoranConfigurator")
                .getDeclaredConstructor(LoggingInitializationContext.class);
        constructor.setAccessible(true);
        var configurator = (JoranConfigurator) constructor.newInstance(new LoggingInitializationContext(environment));
        var context = new LoggerContext();
        context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
        try {
            configurator.setContext(context);
            configurator.doConfigure(getClass().getResource("/logback-spring.xml"));
            var appender = (RollingFileAppender<ILoggingEvent>) context.getLogger("ROOT").getAppender("DAILY_FILE");
            assertTrue(appender.isStarted());
            var policy = (SizeAndTimeBasedRollingPolicy<ILoggingEvent>) appender.getRollingPolicy();
            assertEquals(14, policy.getMaxHistory());
            var clock = policy.getTimeBasedFileNamingAndTriggeringPolicy();
            var today = Instant.ofEpochMilli(clock.getCurrentTime()).atZone(ZoneId.systemDefault()).toLocalDate();
            var logger = context.getLogger("daily-test");
            logger.info("before-rollover");
            var first = directory.resolve(today.toString()).resolve("worker.0.log");
            assertTrue(Files.readString(first).contains("before-rollover"));
            assertTrue(Files.readString(first).contains("[logging-test]"));
            clock.setCurrentTime(
                    today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 1000);
            logger.info("after-rollover");
            var second = directory.resolve(today.plusDays(1).toString()).resolve("worker.0.log");
            assertTrue(Files.readString(second).contains("after-rollover"));
            assertFalse(Files.readString(first).contains("after-rollover"));
            assertFalse(Files.exists(directory.resolve("worker.log")));
        } finally {
            context.stop();
        }
    }
}
