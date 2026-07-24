package io.github.redisops.sync.protocol;

public class RespProtocolException extends RuntimeException {
    public RespProtocolException(String message) {
        super(message);
    }
    public RespProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
