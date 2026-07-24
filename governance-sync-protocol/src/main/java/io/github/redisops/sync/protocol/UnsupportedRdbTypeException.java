package io.github.redisops.sync.protocol;

public class UnsupportedRdbTypeException extends RespProtocolException {
    private final int type;
    public UnsupportedRdbTypeException(int type, String message) {
        super(message);
        this.type = type;
    }
    public int type() {
        return type;
    }
}
