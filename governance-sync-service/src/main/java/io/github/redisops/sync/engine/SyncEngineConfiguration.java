package io.github.redisops.sync.engine;

import io.github.redisops.domain.sync.SyncTask;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SyncEngineConfiguration {

    @Bean
    @ConditionalOnMissingBean(SyncTaskRunnerFactory.class)
    SyncTaskRunnerFactory pendingSyncTaskRunnerFactory() {
        return (task, recovery) -> new PendingSyncTaskRunner(task);
    }

    private static final class PendingSyncTaskRunner implements SyncTaskRunner {
        private final SyncTask task;

        private PendingSyncTaskRunner(SyncTask task) {
            this.task = task;
        }

        @Override
        public void prepare() {
            throw new IllegalStateException(
                    "native replication runner is not available for task " + task.taskNo());
        }

        @Override
        public void start() {
            throw unsupported();
        }

        @Override
        public void pause() {
            throw unsupported();
        }

        @Override
        public void resume() {
            throw unsupported();
        }

        @Override
        public void finish() {
            throw unsupported();
        }

        @Override
        public void cancel() {
            // No resources were allocated.
        }

        @Override
        public void updateLimits(SyncTask task) {
            throw unsupported();
        }

        @Override
        public String phase() {
            return "NOT_IMPLEMENTED";
        }

        @Override
        public long spoolBytes() {
            return 0;
        }

        @Override
        public void close() {
            // No resources were allocated.
        }

        private IllegalStateException unsupported() {
            return new IllegalStateException(
                    "native replication runner is not available for task " + task.taskNo());
        }
    }
}
