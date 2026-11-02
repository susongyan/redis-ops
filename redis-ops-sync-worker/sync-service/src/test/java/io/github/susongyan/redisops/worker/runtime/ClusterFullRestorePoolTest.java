package io.github.susongyan.redisops.worker.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.susongyan.redisops.worker.protocol.RedisSlot;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Unit tests for {@link ClusterFullRestorePool} batch assembly and per-slot dispatch. */
class ClusterFullRestorePoolTest {

    private static TargetCommandSession.RestoreRequest request(String key, int payloadLength) {
        byte[] keyBytes = key.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return new TargetCommandSession.RestoreRequest(keyBytes, -1, new byte[payloadLength]);
    }

    /** Finds keys whose CRC16 slot equals the wanted slot value. */
    private static List<String> keysInDistinctSlots(int wantedSlots, int perSlot) {
        List<Integer> remaining = new ArrayList<>();
        for (int slot = 0; slot < wantedSlots; slot++)
            remaining.add(slot);
        List<String>[] found = new List[wantedSlots];
        for (int i = 0; i < wantedSlots; i++)
            found[i] = new ArrayList<>();
        int counter = 0;
        while (!remaining.isEmpty()) {
            String key = "k" + counter++;
            int slot = RedisSlot.of(key.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            if (slot < wantedSlots && found[slot].size() < perSlot) {
                found[slot].add(key);
                if (found[slot].size() == perSlot)
                    remaining.remove(Integer.valueOf(slot));
            }
        }
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < wantedSlots; i++)
            keys.addAll(found[i]);
        return keys;
    }

    @Test
    void batchesPreserveOrderWithinALaneAndGroupPerNode() throws Exception {
        ClusterTargetRouter router = Mockito.mock(ClusterTargetRouter.class);
        List<List<TargetCommandSession.RestoreRequest>> batches = new CopyOnWriteArrayList<>();
        stubRestore(router, batches);
        TargetFence fence = new TargetFence("epoch", 1, "runtime", "worker", Instant.now());

        try (ClusterFullRestorePool pool = new ClusterFullRestorePool(router, fence,
                new LeaseGuard(java.time.Duration.ZERO),
                1, 64, 1 << 20, 100, 1 << 20, () -> {
                }, () -> {
                }, (lane, keys, bytes) -> {
                })) {
            List<String> keys = keysInDistinctSlots(3, 4);
            for (String key : keys)
                pool.submit(request(key, 8));
            pool.awaitCompletion();
        }

        assertTrue(batches.size() >= 1);
        List<String> appliedOrder = new ArrayList<>();
        for (List<TargetCommandSession.RestoreRequest> batch : batches) {
            assertTrue(batch.size() <= 100);
            batch.forEach(item -> appliedOrder.add(new String(item.key(), java.nio.charset.StandardCharsets.US_ASCII)));
        }
        List<String> submitted = keysInDistinctSlots(3, 4);
        assertEquals(submitted, appliedOrder, "a single lane must apply keys in submit order");
    }

    @Test
    void transactionMaxBytesSplitsLargeItemsIntoSingleItemBatches() throws Exception {
        ClusterTargetRouter router = Mockito.mock(ClusterTargetRouter.class);
        List<List<TargetCommandSession.RestoreRequest>> batches = new CopyOnWriteArrayList<>();
        stubRestore(router, batches);
        TargetFence fence = new TargetFence("epoch", 1, "runtime", "worker", Instant.now());

        // 3 items of ~733 bytes each against a 1KB cap: no batch may hold two of them.
        try (ClusterFullRestorePool pool = new ClusterFullRestorePool(router, fence,
                new LeaseGuard(java.time.Duration.ZERO),
                1, 64, 1 << 20, 100, 1024, () -> {
                }, () -> {
                }, (lane, keys, bytes) -> {
                })) {
            for (int i = 0; i < 3; i++)
                pool.submit(request("big" + i, 600));
            pool.awaitCompletion();
        }

        assertEquals(3, batches.size());
        batches.forEach(batch -> assertEquals(1, batch.size()));
    }

    @Test
    void stopDrainsPartialBatchAndProgressCountsEveryKey() throws Exception {
        ClusterTargetRouter router = Mockito.mock(ClusterTargetRouter.class);
        List<List<TargetCommandSession.RestoreRequest>> batches = new CopyOnWriteArrayList<>();
        stubRestore(router, batches);
        List<Long> progressKeys = new CopyOnWriteArrayList<>();
        TargetFence fence = new TargetFence("epoch", 1, "runtime", "worker", Instant.now());

        try (ClusterFullRestorePool pool = new ClusterFullRestorePool(router, fence,
                new LeaseGuard(java.time.Duration.ZERO),
                2, 64, 1 << 20, 1000, 1 << 20, () -> {
                }, () -> {
                },
                (lane, keys, bytes) -> progressKeys.add(keys))) {
            for (int i = 0; i < 7; i++)
                pool.submit(request("tail" + i, 4));
            pool.awaitCompletion();
        }

        long total = progressKeys.stream().mapToLong(Long::longValue).sum();
        assertEquals(7, total, "progress must count every key exactly once");
    }

    @Test
    void laneFailureFailsSubsequentSubmits() throws Exception {
        ClusterTargetRouter router = Mockito.mock(ClusterTargetRouter.class);
        TargetFence fence = new TargetFence("epoch", 1, "runtime", "worker", Instant.now());
        Mockito.doThrow(new IOException("target down")).when(router)
                .restore(Mockito.anyList(), Mockito.eq(fence), Mockito.any(LeaseGuard.class), Mockito.anyInt());

        ClusterFullRestorePool pool = new ClusterFullRestorePool(router, fence,
                new LeaseGuard(java.time.Duration.ZERO), 1, 64, 1 << 20, 100, 1 << 20,
                () -> {
                }, () -> {
                }, (lane, keys, bytes) -> {
                });
        try {
            pool.submit(request("boom", 4));
            assertThrows(IOException.class, pool::awaitCompletion);
            assertThrows(IOException.class, () -> pool.submit(request("after", 4)));
        } finally {
            pool.close();
        }
    }

    private static void stubRestore(ClusterTargetRouter router,
            List<List<TargetCommandSession.RestoreRequest>> batches) throws IOException {
        Mockito.doAnswer(invocation -> {
            batches.add(List.copyOf(invocation.getArgument(0)));
            return null;
        }).when(router).restore(Mockito.anyList(), Mockito.any(TargetFence.class),
                Mockito.any(LeaseGuard.class), Mockito.anyInt());
        Mockito.doAnswer(invocation -> {
            batches.add(java.util.Collections.singletonList(invocation.getArgument(0)));
            return null;
        }).when(router).restore(Mockito.any(TargetCommandSession.RestoreRequest.class),
                Mockito.any(TargetFence.class), Mockito.any(LeaseGuard.class), Mockito.anyInt());
    }
}
