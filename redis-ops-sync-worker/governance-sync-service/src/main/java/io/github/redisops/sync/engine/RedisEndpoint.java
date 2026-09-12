package io.github.redisops.sync.engine;

record RedisEndpoint(String host, int port) {
    static RedisEndpoint parse(String value) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Redis endpoint is empty");
        String host;
        String portText;
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end < 0 || end + 2 > value.length() || value.charAt(end + 1) != ':')
                throw new IllegalArgumentException("invalid Redis endpoint");
            host = value.substring(1, end);
            portText = value.substring(end + 2);
        } else {
            int separator = value.lastIndexOf(':');
            if (separator < 1)
                throw new IllegalArgumentException("invalid Redis endpoint");
            host = value.substring(0, separator);
            portText = value.substring(separator + 1);
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("invalid Redis endpoint port", error);
        }
        if (port < 1 || port > 65535)
            throw new IllegalArgumentException("invalid Redis endpoint port");
        return new RedisEndpoint(host, port);
    }
}
