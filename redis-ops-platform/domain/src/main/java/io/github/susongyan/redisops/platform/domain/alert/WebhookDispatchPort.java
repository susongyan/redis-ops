package io.github.susongyan.redisops.platform.domain.alert;

public interface WebhookDispatchPort {
    void dispatch(char[] url, String body);
}
