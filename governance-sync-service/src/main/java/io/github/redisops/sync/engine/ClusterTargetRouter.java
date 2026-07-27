package io.github.redisops.sync.engine;

import io.github.redisops.domain.asset.RedisConnectionProfile;
import io.github.redisops.sync.protocol.CommandPlan;
import io.github.redisops.sync.protocol.RedisSlot;
import io.github.redisops.sync.protocol.RespProtocolException;

import java.io.IOException;
import java.time.Duration;
import java.util.*;

final class ClusterTargetRouter implements AutoCloseable {
    private final RedisConnectionProfile profile;
    private final RedisDataEndpointResolver endpoints;
    private final long taskId;
    private final String channel;
    private final Duration connectTimeout;
    private final MasterConnection[] slots = new MasterConnection[16384];
    private final Map<RedisEndpoint, MasterConnection> connections = new LinkedHashMap<>();
    private final BitSet publishedFences = new BitSet(16384);
    private int anchorSlot = -1;

    ClusterTargetRouter(RedisConnectionProfile profile, RedisDataEndpointResolver endpoints, long taskId,
            String channel, Duration connectTimeout) throws IOException {
        this.profile = profile;
        this.endpoints = endpoints;
        this.taskId = taskId;
        this.channel = channel;
        this.connectTimeout = connectTimeout;
        refreshTopology();
    }

    void refreshTopology() throws IOException {
        List<RedisDataEndpointResolver.ClusterMaster> masters = endpoints.resolveClusterMasters(profile);
        MasterConnection[] refreshed = new MasterConnection[16384];
        for (RedisDataEndpointResolver.ClusterMaster master : masters) {
            MasterConnection connection = connections.computeIfAbsent(master.endpoint(),
                    endpoint -> new MasterConnection(endpoint, master.slotStart()));
            for (int slot = master.slotStart(); slot <= master.slotEnd(); slot++)
                refreshed[slot] = connection;
        }
        for (int slot = 0; slot < refreshed.length; slot++)
            if (refreshed[slot] == null)
                throw new IllegalStateException("target Cluster has no owner for slot " + slot);
        synchronized (publishedFences) {
            for (int slot = 0; slot < refreshed.length; slot++)
                if (slots[slot] != refreshed[slot])
                    publishedFences.clear(slot);
        }
        System.arraycopy(refreshed, 0, slots, 0, slots.length);
    }

    OptionalLong publishFences(BitSet ownedSlots, TargetFence fence, LeaseGuard leaseGuard) throws IOException {
        anchorSlot = ownedSlots.nextSetBit(0);
        if (anchorSlot < 0)
            throw new IllegalArgumentException("Cluster channel owns no slots");
        TargetCommandSession.FencePublication publication;
        synchronized (publishedFences) {
            publication = connection(anchorSlot).publishCursorFence(fence, leaseGuard, anchorSlot);
            publishedFences.set(anchorSlot);
        }
        return publication.checkpoint().isPresent()
                ? OptionalLong.of(publication.checkpoint().get().appliedOffset())
                : OptionalLong.empty();
    }

    void initializeFullCheckpoint(BitSet ownedSlots, TargetCheckpoint checkpoint, TargetFence fence,
            LeaseGuard leaseGuard) throws IOException {
        requireAnchor();
        ensureFence(anchorSlot, fence, leaseGuard);
        connection(anchorSlot).applyCursor(checkpoint, fence, leaseGuard, anchorSlot);
    }

    void restore(TargetCommandSession.RestoreRequest request, TargetFence fence, LeaseGuard leaseGuard, int lane)
            throws IOException {
        int slot = RedisSlot.of(request.key());
        ensureFence(slot, fence, leaseGuard);
        try {
            connection(slot).restore(List.of(request), fence, leaseGuard, slot, lane);
        } catch (IOException | RespProtocolException error) {
            refreshTopology();
            ensureFence(slot, fence, leaseGuard);
            connection(slot).restore(List.of(request), fence, leaseGuard, slot, lane);
        }
    }

    void apply(List<CommandPlan.PlannedCommand> commands, TargetCheckpoint checkpoint, TargetFence fence,
            LeaseGuard leaseGuard) throws IOException {
        Map<Integer, List<CommandPlan.PlannedCommand>> bySlot = new LinkedHashMap<>();
        for (CommandPlan.PlannedCommand command : commands) {
            if (command.slot() < 0)
                throw new SyncBlockedException("BLOCKED_CLUSTER_COMMAND",
                        "target Cluster command does not have a Redis slot");
            bySlot.computeIfAbsent(command.slot(), ignored -> new ArrayList<>()).add(command);
        }
        for (Map.Entry<Integer, List<CommandPlan.PlannedCommand>> entry : bySlot.entrySet()) {
            ensureFence(entry.getKey(), fence, leaseGuard);
            try {
                connection(entry.getKey()).apply(entry.getValue(), checkpoint, fence, leaseGuard, entry.getKey());
            } catch (IOException | RespProtocolException error) {
                refreshTopology();
                ensureFence(entry.getKey(), fence, leaseGuard);
                connection(entry.getKey()).apply(entry.getValue(), checkpoint, fence, leaseGuard, entry.getKey());
            }
        }
        requireAnchor();
        ensureFence(anchorSlot, fence, leaseGuard);
        connection(anchorSlot).applyCursor(checkpoint, fence, leaseGuard, anchorSlot);
    }

    Optional<TargetCheckpoint> checkpoint() throws IOException {
        requireAnchor();
        return connection(anchorSlot).cursor(anchorSlot);
    }

    void loadFunction(byte[] payload, TargetFence fence, LeaseGuard leaseGuard) throws IOException {
        for (MasterConnection connection : new LinkedHashSet<>(Arrays.asList(slots))) {
            ensureFence(connection.bootstrapSlot, fence, leaseGuard);
            connection.loadFunction(payload, fence, leaseGuard);
        }
    }

    @Override
    public void close() {
        for (MasterConnection connection : connections.values())
            connection.close();
        connections.clear();
    }

    private MasterConnection connection(int slot) {
        if (slot < 0 || slot >= slots.length)
            throw new IllegalArgumentException("invalid Redis slot");
        return slots[slot];
    }

    private TargetCommandSession.FencePublication ensureFence(int slot, TargetFence fence, LeaseGuard leaseGuard)
            throws IOException {
        synchronized (publishedFences) {
            if (publishedFences.get(slot))
                return new TargetCommandSession.FencePublication(fence, Optional.empty());
            TargetCommandSession.FencePublication publication = connection(slot)
                    .publishFence(fence, leaseGuard, slot);
            publishedFences.set(slot);
            return publication;
        }
    }

    private void requireAnchor() {
        if (anchorSlot < 0)
            throw new IllegalStateException("Cluster target fence has not been published");
    }

    private final class MasterConnection implements AutoCloseable {
        private final RedisEndpoint endpoint;
        private final int bootstrapSlot;
        private TargetCommandSession session;

        private MasterConnection(RedisEndpoint endpoint, int bootstrapSlot) {
            this.endpoint = endpoint;
            this.bootstrapSlot = bootstrapSlot;
        }

        synchronized TargetCommandSession.FencePublication publishFence(TargetFence fence, LeaseGuard leaseGuard,
                int slot) throws IOException {
            return session().publishFence(fence, leaseGuard, taskId, channel, slot);
        }

        synchronized TargetCommandSession.FencePublication publishCursorFence(TargetFence fence,
                LeaseGuard leaseGuard, int slot) throws IOException {
            return session().publishCursorFence(fence, leaseGuard, taskId, channel, slot);
        }

        synchronized TargetCheckpoint apply(List<CommandPlan.PlannedCommand> commands, TargetCheckpoint checkpoint,
                TargetFence fence, LeaseGuard leaseGuard, int slot) throws IOException {
            try {
                return session().apply(commands, checkpoint, fence, leaseGuard, taskId, channel, slot);
            } catch (IOException error) {
                reconnect();
                return session().apply(commands, checkpoint, fence, leaseGuard, taskId, channel, slot);
            }
        }

        synchronized void restore(List<TargetCommandSession.RestoreRequest> requests, TargetFence fence,
                LeaseGuard leaseGuard, int slot, int lane) throws IOException {
            try {
                session().restoreBatch(requests, fence, lane, leaseGuard, taskId, channel, slot);
            } catch (IOException error) {
                reconnect();
                session().restoreBatch(requests, fence, lane, leaseGuard, taskId, channel, slot);
            }
        }

        synchronized Optional<TargetCheckpoint> cursor(int slot) throws IOException {
            return session().cursor(taskId, channel, slot);
        }

        synchronized TargetCheckpoint applyCursor(TargetCheckpoint checkpoint, TargetFence fence,
                LeaseGuard leaseGuard, int slot) throws IOException {
            try {
                return session().applyCursor(checkpoint, fence, leaseGuard, taskId, channel, slot);
            } catch (IOException error) {
                reconnect();
                return session().applyCursor(checkpoint, fence, leaseGuard, taskId, channel, slot);
            }
        }

        synchronized void loadFunction(byte[] payload, TargetFence fence, LeaseGuard leaseGuard)
                throws IOException {
            session().loadFunction(payload, fence, leaseGuard);
        }

        private TargetCommandSession session() throws IOException {
            if (session == null)
                session = TargetCommandSession.clusterSlot(profile, endpoint, taskId, connectTimeout, channel,
                        bootstrapSlot);
            return session;
        }

        private void reconnect() {
            closeQuietly(session);
            session = null;
        }

        @Override
        public synchronized void close() {
            reconnect();
        }
    }

    static BitSet allSlots() {
        BitSet result = new BitSet(16384);
        result.set(0, 16384);
        return result;
    }

    static BitSet slots(List<RedisDataEndpointResolver.ClusterMaster> ranges, RedisEndpoint endpoint) {
        BitSet result = new BitSet(16384);
        for (RedisDataEndpointResolver.ClusterMaster range : ranges)
            if (range.endpoint().equals(endpoint))
                result.set(range.slotStart(), range.slotEnd() + 1);
        return result;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null)
            return;
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Best effort during topology refresh and shutdown.
        }
    }
}
