package io.github.redisops.common;

public class BusinessException extends RuntimeException {
    private final String code;

    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() { return code; }

    public static BusinessException notFound(String resource, long id) {
        return new BusinessException("RESOURCE_NOT_FOUND", resource + " not found: " + id);
    }
}
