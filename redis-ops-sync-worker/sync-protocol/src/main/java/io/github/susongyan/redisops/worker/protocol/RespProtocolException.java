package io.github.susongyan.redisops.worker.protocol;

public class RespProtocolException extends RuntimeException {
    public RespProtocolException(String message) {
        super(message);
    }
    public RespProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
