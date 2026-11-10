package io.github.susongyan.redisops.worker.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.susongyan.redisops.sync.contract.SyncContractStatus;
import io.github.susongyan.redisops.worker.domain.WorkerSyncTask;
import io.github.susongyan.redisops.worker.persistence.WorkerAssetReadPort;

/**
 * Unit coverage of the P3 precheck additions (design doc §5.4): the pure sample classification and
 * the gating of the two new checks. Splitting disabled or sampling off must produce PASSED skip
 * entries without ever opening a Redis connection (the profile lambda points at a dead port).
 */
class SyncPrecheckSampleTest {
    private final WorkerAssetReadPort assets = Mockito.mock(WorkerAssetReadPort.class);
    private final RedisDataEndpointResolver endpoints = Mockito.mock(RedisDataEndpointResolver.class);

    private static final long MAX_RESTORE = 8192;
    private static final long HARD_CAP = 65536;

    private SyncPrecheckExecutor fixture(long maxRestoreBytes, int sampleKeys) {
        var split = new SplitKeySettingsConfig(maxRestoreBytes, 4096, 8, 4 * 1024 * 1024L, HARD_CAP,
                "FAIL", sampleKeys);
        // Dead port: any session open would fail and surface as a WARNING, failing the assertions.
        WorkerRedisConnectionProfilePort profiles = id -> new WorkerRedisConnectionProfile(id,
                WorkerClusterMode.STANDALONE, List.of("127.0.0.1:1"), null, null, "NONE", null);
        return new SyncPrecheckExecutor(assets, profiles, null, null, null, new ObjectMapper(), endpoints,
                split, Path.of("data"), 1024, 300000);
    }

    private static WorkerSyncTask task(String policyJson) {
        Instant now = Instant.now();
        return new WorkerSyncTask(1L, "SYNC", null, 1, 2, "MIGRATION", "FULL_AND_INCREMENTAL",
                SyncContractStatus.STARTING, "NATIVE_JAVA", 0, 0, "[\"*\"]", "[]", policyJson, "PRESERVE",
                50000, 100000000, 1048576, 4, 8, "START", true, "test", null, "epoch", null, null, 0,
                now, now, null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sampleEntry(SyncPrecheckExecutor executor, WorkerSyncTask task) throws Exception {
        List<Map<String, Object>> checks = new ArrayList<>();
        var method = SyncPrecheckExecutor.class.getDeclaredMethod("sourceKeySampleRisk", List.class,
                WorkerSyncTask.class);
        method.setAccessible(true);
        method.invoke(executor, checks, task);
        return checks.get(0);
    }

    private String bulkLimitMessage(SyncPrecheckExecutor executor, WorkerSyncTask task) throws Exception {
        var method = SyncPrecheckExecutor.class.getDeclaredMethod("targetBulkLimit", WorkerSyncTask.class);
        method.setAccessible(true);
        return (String) method.invoke(executor, task);
    }

    @Test
    void samplingSkipsWithoutConnectionsWhenSplittingDisabledByThreshold() throws Exception {
        var entry = sampleEntry(fixture(0, 5000), task("{\"allowSafeSplit\":true}"));
        assertEquals("PASSED", entry.get("status"));
        assertTrue(((String) entry.get("message")).contains("拆分未启用"));
    }

    @Test
    void samplingSkipsWithoutConnectionsWhenPolicyDisallowsSafeSplit() throws Exception {
        // Jackson leaves allowSafeSplit false when absent — same trap the P1 IT hit.
        var entry = sampleEntry(fixture(MAX_RESTORE, 5000), task("{\"policyVersion\":\"v3\"}"));
        assertEquals("PASSED", entry.get("status"));
        assertTrue(((String) entry.get("message")).contains("拆分未启用"));
    }

    @Test
    void samplingSkipsWhenDisabledByBudget() throws Exception {
        var entry = sampleEntry(fixture(MAX_RESTORE, 0), task("{\"allowSafeSplit\":true}"));
        assertEquals("PASSED", entry.get("status"));
        assertTrue(((String) entry.get("message")).contains("采样已关闭"));
    }

    @Test
    void bulkLimitCheckSkipsWhenSplittingDisabled() throws Exception {
        var message = bulkLimitMessage(fixture(0, 5000), task("{\"allowSafeSplit\":true}"));
        assertTrue(message.contains("splitting disabled"));
    }

    @Test
    void bulkLimitCheckSkipsWhenPolicyDisallowsSafeSplit() throws Exception {
        var message = bulkLimitMessage(fixture(MAX_RESTORE, 5000), task("{\"policyVersion\":\"v3\"}"));
        assertTrue(message.contains("splitting disabled"));
    }

    @Test
    void classifySampleCoversModuleTypesOverHardCapAndPathEstimates() {
        // Vanished between SCAN and probe.
        assertEquals(SyncPrecheckExecutor.SampleClass.SKIP,
                SyncPrecheckExecutor.classifySample("none", null, MAX_RESTORE, HARD_CAP));
        assertEquals(SyncPrecheckExecutor.SampleClass.SKIP,
                SyncPrecheckExecutor.classifySample("string", null, MAX_RESTORE, HARD_CAP));
        // Module types surface under their module-defined type name, at any size.
        assertEquals(SyncPrecheckExecutor.SampleClass.UNSUPPORTED_TYPE,
                SyncPrecheckExecutor.classifySample("ReJSON-RL", 100L, MAX_RESTORE, HARD_CAP));
        assertEquals(SyncPrecheckExecutor.SampleClass.UNSUPPORTED_TYPE,
                SyncPrecheckExecutor.classifySample("graph", 10 * HARD_CAP, MAX_RESTORE, HARD_CAP));
        // Over the hard cap: skipped with an alert at runtime regardless of type.
        assertEquals(SyncPrecheckExecutor.SampleClass.OVER_HARD_CAP,
                SyncPrecheckExecutor.classifySample("string", HARD_CAP + 1, MAX_RESTORE, HARD_CAP));
        assertEquals(SyncPrecheckExecutor.SampleClass.OVER_HARD_CAP,
                SyncPrecheckExecutor.classifySample("hash", 10 * HARD_CAP, MAX_RESTORE, HARD_CAP));
        // Above the split threshold: splittable collections ride the split path.
        assertEquals(SyncPrecheckExecutor.SampleClass.SPLIT_PATH,
                SyncPrecheckExecutor.classifySample("list", MAX_RESTORE + 1, MAX_RESTORE, HARD_CAP));
        assertEquals(SyncPrecheckExecutor.SampleClass.SPLIT_PATH,
                SyncPrecheckExecutor.classifySample("zset", MAX_RESTORE * 2, MAX_RESTORE, HARD_CAP));
        // Strings and streams are not splittable (P1.5 deferred): fallback RESTORE.
        assertEquals(SyncPrecheckExecutor.SampleClass.FALLBACK_RESTORE,
                SyncPrecheckExecutor.classifySample("string", MAX_RESTORE + 1, MAX_RESTORE, HARD_CAP));
        assertEquals(SyncPrecheckExecutor.SampleClass.FALLBACK_RESTORE,
                SyncPrecheckExecutor.classifySample("stream", MAX_RESTORE + 1, MAX_RESTORE, HARD_CAP));
        // Ordinary keys stay off every list.
        assertEquals(SyncPrecheckExecutor.SampleClass.SKIP,
                SyncPrecheckExecutor.classifySample("string", 100L, MAX_RESTORE, HARD_CAP));
    }

    @Test
    void configValidatesSampleBudgetAndKeepsBatchInheritance() {
        assertThrows(IllegalArgumentException.class,
                () -> new SplitKeySettingsConfig(MAX_RESTORE, 0, 8, 4 * 1024 * 1024L, HARD_CAP, "FAIL", -1));
        assertEquals(0, new SplitKeySettingsConfig(MAX_RESTORE, 0, 8, 4 * 1024 * 1024L, HARD_CAP, "FAIL", 0)
                .precheckSampleKeys());
        var config = new SplitKeySettingsConfig(MAX_RESTORE, 0, 8, 4 * 1024 * 1024L, HARD_CAP, "SKIP", 5000);
        assertEquals(4 * 1024 * 1024L, config.settings().splitBatchBytes(), "zero batch bytes inherits the cap");
        assertTrue(config.settings().skipUnsupportedTypes());
        assertEquals(5000, config.precheckSampleKeys());
    }
}
