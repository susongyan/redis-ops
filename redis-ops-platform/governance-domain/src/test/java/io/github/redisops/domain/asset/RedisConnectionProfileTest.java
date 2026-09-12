package io.github.redisops.domain.asset;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RedisConnectionProfileTest {
    @Test
    void clearsPasswordWhenClosed() {
        char[] password = "secret".toCharArray();
        RedisConnectionProfile profile = new RedisConnectionProfile(1, ClusterMode.STANDALONE,
                List.of("localhost:6379"), null, null, "PASSWORD", password);
        profile.close();
        assertArrayEquals(new char[]{'\0', '\0', '\0', '\0', '\0', '\0'}, password);
    }
}
