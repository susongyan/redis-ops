package io.github.susongyan.redisops.platform.application.asset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.susongyan.redisops.platform.domain.asset.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClusterPasswordServiceTest {
    @Test
    void readsSavedPasswordAndClearsTemporaryBuffer() {
        var profiles = mock(RedisConnectionProfileProvider.class);
        var secret = "test-only-secret".toCharArray();
        when(profiles.get(7)).thenReturn(new RedisConnectionProfile(7, ClusterMode.STANDALONE,
                List.of("redis:6379"), null, null, "PASSWORD", secret));
        assertEquals("test-only-secret", new ClusterPasswordService(profiles).read(7));
        assertArrayEquals(new char[secret.length], secret);
    }

    @Test
    void unauthenticatedRedisHasNoPasswordAndDecryptionFailuresAreNotHidden() {
        var profiles = mock(RedisConnectionProfileProvider.class);
        when(profiles.get(7)).thenReturn(new RedisConnectionProfile(7, ClusterMode.STANDALONE,
                List.of("redis:6379"), null, null, null, null));
        var service = new ClusterPasswordService(profiles);
        assertNull(service.read(7));
        when(profiles.get(8)).thenThrow(new IllegalStateException("credential decryption failed"));
        assertThrows(IllegalStateException.class, () -> service.read(8));
    }
}
