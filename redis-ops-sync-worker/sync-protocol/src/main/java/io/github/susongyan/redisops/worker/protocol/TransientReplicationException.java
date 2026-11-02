package io.github.susongyan.redisops.worker.protocol;

/** Source-side transient condition (-NOMASTERLINK, -LOADING) that real replicas retry, not a hard failure. */
public class TransientReplicationException extends RespProtocolException {
    public TransientReplicationException(String message) {
        super(message);
    }
}
