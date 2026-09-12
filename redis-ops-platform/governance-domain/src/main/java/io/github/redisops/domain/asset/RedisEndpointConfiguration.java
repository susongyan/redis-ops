package io.github.redisops.domain.asset;

import io.github.redisops.common.BusinessException;

import java.util.Arrays;
import java.util.List;

public record RedisEndpointConfiguration(List<String> seedEndpoints, String sentinelMasterName) {
    public RedisEndpointConfiguration {
        seedEndpoints = List.copyOf(seedEndpoints);
    }

    public static RedisEndpointConfiguration parse(ClusterMode mode, String configuredEndpoint) {
        if (mode == null)
            throw invalid("mode is required");
        if (configuredEndpoint == null || configuredEndpoint.isBlank())
            throw invalid("at least one endpoint is required");

        String endpointList = configuredEndpoint.trim();
        String masterName = null;
        if (mode == ClusterMode.SENTINEL) {
            int separator = endpointList.indexOf('@');
            if (separator < 1 || separator == endpointList.length() - 1)
                throw invalid("Sentinel endpoint must be masterName@host:port[,host:port]");
            masterName = endpointList.substring(0, separator).trim();
            endpointList = endpointList.substring(separator + 1);
        } else if (endpointList.contains("@")) {
            throw invalid("only Sentinel endpoints may contain a master name");
        }

        List<String> seeds = Arrays.stream(endpointList.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .peek(RedisEndpointConfiguration::validateHostPort)
                .distinct()
                .toList();
        if (seeds.isEmpty())
            throw invalid("at least one endpoint is required");
        return new RedisEndpointConfiguration(seeds, masterName);
    }

    private static void validateHostPort(String endpoint) {
        int separator = endpoint.lastIndexOf(':');
        if (separator < 1 || separator == endpoint.length() - 1)
            throw invalid("endpoint must be host:port");
        String host = endpoint.substring(0, separator).trim();
        if (host.startsWith("[")) {
            if (!host.endsWith("]") || host.length() <= 2)
                throw invalid("IPv6 endpoint must use [address]:port");
        } else if (host.contains(":")) {
            throw invalid("IPv6 endpoint must use [address]:port");
        }
        if (host.isBlank())
            throw invalid("endpoint host is required");
        try {
            int port = Integer.parseInt(endpoint.substring(separator + 1));
            if (port < 1 || port > 65535)
                throw invalid("endpoint port must be between 1 and 65535");
        } catch (NumberFormatException exception) {
            throw invalid("endpoint port is invalid");
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException("INVALID_ENDPOINT", message);
    }
}
