package io.github.redisops.worker.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class WorkerRedisConnectionProfileTest {
    @Test
    void clearsPasswordWhenClosed() {
        char[] password = "secret".toCharArray();
        WorkerRedisConnectionProfile profile = new WorkerRedisConnectionProfile(1, WorkerClusterMode.STANDALONE,
                List.of("localhost:6379"), null, null, "PASSWORD", password);

        profile.close();

        assertArrayEquals(new char[]{'\0', '\0', '\0', '\0', '\0', '\0'}, password);
    }
}
