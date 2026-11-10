package io.github.susongyan.redisops.worker.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.lettuce.core.RedisClient;
import io.github.susongyan.redisops.sync.contract.SyncContractStatus;
import io.github.susongyan.redisops.worker.domain.WorkerSyncTask;
import io.github.susongyan.redisops.worker.persistence.WorkerAssetReadPort;

/**
 * Live P3 precheck sampling against the scratch fixture on 6390/6391 (start with
 * REDIS_SYNC_IT=true; never point this at a business instance — the test flushes the source db).
 * Module types cannot be planted without a real module, so their classification stays unit-tested;
 * the deterministic live finding is the over-hard-cap key.
 */
@EnabledIfEnvironmentVariable(named = "REDIS_SYNC_IT", matches = "true")
class SyncPrecheckSamplingRedisTest {
    private final WorkerAssetReadPort assets = Mockito.mock(WorkerAssetReadPort.class);

    private SyncPrecheckExecutor executor(long maxRestoreBytes, long hardCapBytes) throws Exception {
        var split = new SplitKeySettingsConfig(maxRestoreBytes, 4096, 8, 4 * 1024 * 1024L, hardCapBytes,
                "FAIL", 5000);
        WorkerRedisConnectionProfilePort profiles = id -> new WorkerRedisConnectionProfile(id,
                WorkerClusterMode.STANDALONE, List.of("127.0.0.1:1"), null, null, "NONE", null);
        var endpoints = Mockito.mock(RedisDataEndpointResolver.class);
        Mockito.when(endpoints.resolvePrimary(Mockito.any())).thenAnswer(call -> {
            var profile = (WorkerRedisConnectionProfile) call.getArgument(0);
            return new RedisEndpoint("127.0.0.1", (int) (profile.clusterId() == 1 ? 6390 : 6391));
        });
        return new SyncPrecheckExecutor(assets, profiles, null, null, null, new ObjectMapper(), endpoints,
                split, Path.of("data"), 1024, 300000);
    }

    private static WorkerSyncTask task() {
        Instant now = Instant.now();
        return new WorkerSyncTask(1L, "SYNC", null, 1, 2, "MIGRATION", "FULL_AND_INCREMENTAL",
                SyncContractStatus.STARTING, "NATIVE_JAVA", 0, 0, "[\"*\"]", "[]",
                "{\"allowSafeSplit\":true}", "PRESERVE", 50000, 100000000, 1048576, 4, 8, "START", true,
                "test", null, "epoch", null, null, 0, now, now, null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sampleEntry(SyncPrecheckExecutor executor) throws Exception {
        List<Map<String, Object>> checks = new ArrayList<>();
        var method = SyncPrecheckExecutor.class.getDeclaredMethod("sourceKeySampleRisk", List.class,
                WorkerSyncTask.class);
        method.setAccessible(true);
        method.invoke(executor, checks, task());
        return checks.get(0);
    }

    private String bulkLimitMessage(SyncPrecheckExecutor executor) throws Exception {
        var method = SyncPrecheckExecutor.class.getDeclaredMethod("targetBulkLimit", WorkerSyncTask.class);
        method.setAccessible(true);
        return (String) method.invoke(executor, task());
    }

    @Test
    void samplingWarnsOnOversizeKeysAndBulkLimitHoldsOnLiveTarget() throws Exception {
        RedisClient sourceClient = RedisClient.create("redis://127.0.0.1:6390");
        RedisClient targetClient = RedisClient.create("redis://127.0.0.1:6391");
        try (var source = sourceClient.connect(); var ignored = targetClient.connect()) {
            source.sync().flushall();
            source.sync().set("small:str", "ok");
            source.sync().rpush("big:list", "m".repeat(100) + ":0");
            // Incompressible 200KB string: over the 64KB hard cap whatever LZF does.
            var random = new java.util.Random(42);
            var huge = new StringBuilder(200_000);
            String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
            for (int i = 0; i < 200_000; i++)
                huge.append(alphabet.charAt(random.nextInt(alphabet.length())));
            source.sync().set("huge:str", huge.toString());

            var entry = sampleEntry(executor(8192, 65536));
            assertEquals("WARNING", entry.get("status"));
            List<Map<String, Object>> findings = (List<Map<String, Object>>) entry.get("findings");
            assertNotNull(findings);
            assertTrue(findings.stream().anyMatch(finding -> "OVER_HARD_CAP".equals(finding.get("kind"))
                    && "string".equals(finding.get("type"))
                    && ((Number) finding.get("count")).longValue() >= 1),
                    "the over-hard-cap string must surface as a finding: " + findings);
            var sample = (Map<String, Object>) entry.get("sample");
            assertEquals(1, ((Number) sample.get("nodes")).intValue());
            assertTrue(((Number) sample.get("scannedKeys")).longValue() >= 3);
            assertTrue(((Number) sample.get("inScopeKeys")).longValue() >= 3);

            // Live proto-max-bulk-len (Redis default 512MB) comfortably above the 64KB hard cap.
            assertTrue(bulkLimitMessage(executor(8192, 65536)).contains("proto-max-bulk-len min="));

            // A hard cap above the live limit must fail the check (design doc §5.2 invariant).
            var misconfigured = executor(8192, 600L * 1024 * 1024);
            var error = assertThrows(InvocationTargetException.class, () -> bulkLimitMessage(misconfigured));
            assertTrue(error.getCause().getMessage().contains("must stay below"));
        } finally {
            sourceClient.shutdown();
            targetClient.shutdown();
        }
    }
}
