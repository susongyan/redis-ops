package io.github.redisops.domain.asset;

public record ClusterQuery(String environment, String businessLine, String owner,
                           ClusterStatus status, int page, int size) {
    public ClusterQuery {
        if (page < 1) page = 1;
        if (size < 1) size = 20;
        if (size > 200) size = 200;
    }
    public int offset() { return (page - 1) * size; }
}
