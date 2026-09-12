package io.github.redisops.sync.protocol;

public sealed interface ReplicationReply permits ReplicationReply.FullResync, ReplicationReply.Continue {
    record FullResync(String replicationId, long offset, RdbTransfer transfer) implements ReplicationReply {
    }
    record Continue(String replicationId) implements ReplicationReply {
    }
    record RdbTransfer(long length, String eofMarker) {
        public boolean diskless() {
            return eofMarker != null;
        }
    }
}
