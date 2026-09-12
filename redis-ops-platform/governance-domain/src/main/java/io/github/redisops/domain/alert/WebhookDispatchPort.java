package io.github.redisops.domain.alert;

public interface WebhookDispatchPort {
    void dispatch(char[] url, String body);
}
