package io.github.susongyan.redisops.platform.infrastructure.analysis;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Bounded response handling; never include upstream bodies in errors. */
final class AnalysisResponseBody {
    static final int MAX_BYTES = 1024 * 1024;

    private AnalysisResponseBody() {
    }

    static String read(HttpResponse<String> response) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IOException("analysis agent returned non-success HTTP status");
        return response.body();
    }

    static HttpResponse.BodyHandler<String> handler() {
        return info -> new LimitedSubscriber();
    }

    private static final class LimitedSubscriber implements HttpResponse.BodySubscriber<String> {
        private final HttpResponse.BodySubscriber<String> delegate = HttpResponse.BodySubscribers
                .ofString(StandardCharsets.UTF_8);
        private Flow.Subscription subscription;
        private long bytes;
        private boolean failed;

        public CompletionStage<String> getBody() {
            return delegate.getBody();
        }
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            delegate.onSubscribe(value);
        }
        public void onNext(List<ByteBuffer> items) {
            if (failed)
                return;
            for (ByteBuffer item : items)
                bytes += item.remaining();
            if (bytes > MAX_BYTES) {
                failed = true;
                subscription.cancel();
                delegate.onError(new IOException("analysis response exceeds size limit"));
            } else
                delegate.onNext(items);
        }
        public void onError(Throwable error) {
            if (!failed)
                delegate.onError(error);
        }
        public void onComplete() {
            if (!failed)
                delegate.onComplete();
        }
    }
}
