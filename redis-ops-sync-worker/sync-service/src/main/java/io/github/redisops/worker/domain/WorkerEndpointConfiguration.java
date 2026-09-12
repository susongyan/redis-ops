package io.github.redisops.worker.domain;

import io.github.redisops.worker.runtime.WorkerClusterMode;

import java.util.Arrays;
import java.util.List;

public record WorkerEndpointConfiguration(List<String> seedEndpoints, String sentinelMasterName) {
    public static WorkerEndpointConfiguration parse(WorkerClusterMode mode, String configured) {
        if (mode == null || configured == null || configured.isBlank())
            throw new IllegalArgumentException("Redis endpoint configuration is required");
        String endpoints = configured.trim();
        String master = null;
        if (mode == WorkerClusterMode.SENTINEL) {
            int separator = endpoints.indexOf('@');
            if (separator < 1 || separator == endpoints.length() - 1)
                throw new IllegalArgumentException("Sentinel endpoint must be masterName@host:port[,host:port]");
            master = endpoints.substring(0, separator).trim();
            endpoints = endpoints.substring(separator + 1);
        } else if (endpoints.contains("@")) {
            throw new IllegalArgumentException("only Sentinel endpoints may contain a master name");
        }
        List<String> seeds = Arrays.stream(endpoints.split(",")).map(String::trim).filter(x -> !x.isEmpty())
                .peek(WorkerEndpointConfiguration::validate).distinct().toList();
        if (seeds.isEmpty())
            throw new IllegalArgumentException("at least one Redis endpoint is required");
        return new WorkerEndpointConfiguration(seeds, master);
    }

    private static void validate(String endpoint) {
        int separator = endpoint.lastIndexOf(':');
        if (separator < 1 || separator == endpoint.length() - 1)
            throw new IllegalArgumentException("Redis endpoint must be host:port");
        String host = endpoint.substring(0, separator).trim();
        if (host.startsWith("[")) {
            if (!host.endsWith("]") || host.length() <= 2)
                throw new IllegalArgumentException("IPv6 endpoint must use [address]:port");
        } else if (host.contains(":")) {
            throw new IllegalArgumentException("IPv6 endpoint must use [address]:port");
        }
        if (host.isBlank())
            throw new IllegalArgumentException("Redis endpoint host is required");
        try {
            int port = Integer.parseInt(endpoint.substring(separator + 1));
            if (port < 1 || port > 65535)
                throw new IllegalArgumentException("Redis endpoint port is invalid");
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Redis endpoint port is invalid", error);
        }
    }
}
