package io.github.redisops.platform.infrastructure.analysis;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisResponseBodyTest {
    @Test
    void oversizedResponseCancelsSubscription() {
        var subscriber = AnalysisResponseBody.handler().apply(null);
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.allocate(AnalysisResponseBody.MAX_BYTES + 1)));
        verify(subscription).cancel();
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> subscriber.getBody().toCompletableFuture().join());
    }
    @Test
    void smallResponseCompletes() {
        var subscriber = AnalysisResponseBody.handler().apply(null);
        subscriber.onSubscribe(mock(Flow.Subscription.class));
        subscriber.onNext(List.of(ByteBuffer.wrap("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        subscriber.onComplete();
        assertEquals("{}", subscriber.getBody().toCompletableFuture().join());
    }
}
