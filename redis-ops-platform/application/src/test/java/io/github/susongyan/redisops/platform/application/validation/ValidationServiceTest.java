package io.github.susongyan.redisops.platform.application.validation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.susongyan.redisops.platform.common.BusinessException;
import io.github.susongyan.redisops.platform.domain.asset.ClusterMode;
import io.github.susongyan.redisops.platform.domain.asset.ClusterRepository;
import io.github.susongyan.redisops.platform.domain.asset.ClusterStatus;
import io.github.susongyan.redisops.platform.domain.asset.RedisCluster;
import io.github.susongyan.redisops.platform.application.LocalJobRunner;
import io.github.susongyan.redisops.platform.domain.validation.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ValidationServiceTest {
    @Test
    void strictValidationWithLargeKeyDoesNotPassGate() {
        ValidationRepository repository = mock(ValidationRepository.class);
        RedisValidationPort redis = mock(RedisValidationPort.class);
        ValidationService service = new ValidationService(repository, mock(ClusterRepository.class),
                mock(LocalJobRunner.class), redis);
        ValidationTask checking = task(ValidationTaskStatus.CHECKING);
        ValidationTask running = task(ValidationTaskStatus.RUNNING);
        when(repository.findTask(1)).thenReturn(Optional.of(checking), Optional.of(running), Optional.of(running));
        when(repository.updateTask(any(), anyLong())).thenReturn(true);
        when(repository.saveRun(any())).thenAnswer(invocation -> {
            ValidationRun value = invocation.getArgument(0);
            return value.id() == null
                    ? new ValidationRun(2L, value.taskId(), value.runNo(), value.status(), value.plannedKeys(),
                            value.scannedKeys(), value.comparedKeys(), value.differenceCount(), value.degradedCount(),
                            value.unverifiableCount(), value.inconclusiveCount(), value.startedAt(), value.finishedAt(),
                            value.summaryJson())
                    : value;
        });
        byte[] key = "large-key".getBytes();
        when(redis.scan(eq(11L), eq(0), anyString(), anyInt()))
                .thenReturn(new RedisValidationPort.ScanPage("0", List.of(new RedisValidationPort.ValidationKey(key))));
        when(redis.scan(eq(22L), eq(0), anyString(), anyInt()))
                .thenReturn(new RedisValidationPort.ScanPage("0", List.of()));
        RedisValidationPort.ValidationValue value = new RedisValidationPort.ValidationValue("string",
                128L * 1024 * 1024,
                -1, null, "METADATA", "LARGE_KEY_THRESHOLD");
        when(redis.inspectBatch(anyLong(), anyInt(), anyList(), any())).thenReturn(List.of(Optional.of(value)));

        service.execute(1);

        ArgumentCaptor<ValidationTask> captured = ArgumentCaptor.forClass(ValidationTask.class);
        verify(repository, atLeast(2)).updateTask(captured.capture(), anyLong());
        assertEquals(ValidationTaskStatus.INCONCLUSIVE,
                captured.getAllValues().get(captured.getAllValues().size() - 1).status());
        verify(repository).saveDifferences(argThat(items -> items.size() == 1
                && items.get(0).differenceType() == ValidationDifferenceType.LARGE_KEY_DEGRADED));
    }

    @Test
    void midRunFailureKeepsDifferencesFoundSoFar() {
        ValidationRepository repository = mock(ValidationRepository.class);
        RedisValidationPort redis = mock(RedisValidationPort.class);
        ValidationService service = new ValidationService(repository, mock(ClusterRepository.class),
                mock(LocalJobRunner.class), redis);
        ValidationTask checking = task(ValidationTaskStatus.CHECKING, ValidationStrictness.REPORT);
        ValidationTask running = task(ValidationTaskStatus.RUNNING, ValidationStrictness.REPORT);
        when(repository.findTask(1)).thenReturn(Optional.of(checking), Optional.of(running), Optional.of(running));
        when(repository.updateTask(any(), anyLong())).thenReturn(true);
        when(repository.saveRun(any())).thenAnswer(invocation -> {
            ValidationRun value = invocation.getArgument(0);
            return value.id() == null
                    ? new ValidationRun(2L, value.taskId(), value.runNo(), value.status(), value.plannedKeys(),
                            value.scannedKeys(), value.comparedKeys(), value.differenceCount(), value.degradedCount(),
                            value.unverifiableCount(), value.inconclusiveCount(), value.startedAt(), value.finishedAt(),
                            value.summaryJson())
                    : value;
        });
        // page one reports a key missing on the target, page two dies — the buffered diff must survive the failure
        when(redis.scan(11L, 0, "0", 500)).thenReturn(new RedisValidationPort.ScanPage("1",
                List.of(new RedisValidationPort.ValidationKey("gone".getBytes()))));
        when(redis.scan(11L, 0, "1", 500)).thenThrow(new IllegalStateException("connection reset"));
        RedisValidationPort.ValidationValue value = new RedisValidationPort.ValidationValue("string", 4, -1, "aa",
                "SEMANTIC", null);
        when(redis.inspectBatch(eq(11L), eq(0), anyList(), any())).thenReturn(List.of(Optional.of(value)));
        when(redis.inspectBatch(eq(22L), eq(0), anyList(), any())).thenReturn(List.of(Optional.empty()));

        service.execute(1);

        verify(repository).saveDifferences(argThat(items -> items.size() == 1
                && items.get(0).differenceType() == ValidationDifferenceType.MISSING_TARGET));
        ArgumentCaptor<ValidationRun> runs = ArgumentCaptor.forClass(ValidationRun.class);
        verify(repository, atLeast(2)).saveRun(runs.capture());
        ValidationRun finalRun = runs.getAllValues().get(runs.getAllValues().size() - 1);
        assertEquals("FAILED", finalRun.status());
        assertEquals(1, finalRun.differenceCount());
    }

    @Test
    void startRejectsWhenAnotherValidationRunsOnTheSameClusterPair() {
        ValidationRepository repository = mock(ValidationRepository.class);
        ValidationService service = new ValidationService(repository, mock(ClusterRepository.class),
                mock(LocalJobRunner.class), mock(RedisValidationPort.class));
        when(repository.findTask(1L)).thenReturn(Optional.of(task(ValidationTaskStatus.READY)));
        when(repository.findActiveTaskForPair(11L, 22L))
                .thenReturn(Optional.of(task(ValidationTaskStatus.RUNNING)));
        BusinessException error = assertThrows(BusinessException.class, () -> service.start(1, 0, "key"));
        assertEquals("VALIDATION_CLUSTER_BUSY", error.code());
        verify(repository, never()).updateTask(any(), anyLong());
    }

    @Test
    void emptyPatternListsSerializeAsEmptyArray() {
        ValidationRepository repository = mock(ValidationRepository.class);
        ClusterRepository clusters = mock(ClusterRepository.class);
        when(clusters.findById(11L)).thenReturn(Optional.of(cluster(11L)));
        when(clusters.findById(22L)).thenReturn(Optional.of(cluster(22L)));
        when(repository.saveTask(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ValidationService service = new ValidationService(repository, clusters, mock(LocalJobRunner.class),
                mock(RedisValidationPort.class));
        ValidationTask created = service.create(null, 11L, 22L, 0, 0, ValidationStrictness.REPORT, List.of(),
                List.of(), ValidationSamplingMode.COUNT, 100, null, null, null, null, null, null);
        // [""] would parse back as one empty-string pattern that matches nothing
        assertEquals("[]", created.includePatternsJson());
        assertEquals("[]", created.excludePatternsJson());
    }

    private static RedisCluster cluster(long id) {
        return new RedisCluster(id, "cluster-" + id, "local", "line", "owner", null, null, ClusterMode.CLUSTER,
                "7.0", "127.0.0.1:6379", null, ClusterStatus.ACTIVE, 0, Instant.now(), Instant.now());
    }

    @Test
    void countSampleSpreadsQuotasAcrossClusterShards() {
        ValidationRepository repository = mock(ValidationRepository.class);
        RedisValidationPort redis = mock(RedisValidationPort.class);
        ValidationService service = new ValidationService(repository, mock(ClusterRepository.class),
                mock(LocalJobRunner.class), redis);
        ValidationTask checking = task(ValidationTaskStatus.CHECKING, ValidationStrictness.REPORT);
        ValidationTask running = task(ValidationTaskStatus.RUNNING, ValidationStrictness.REPORT);
        when(repository.findTask(1)).thenReturn(Optional.of(checking), Optional.of(running), Optional.of(running));
        when(repository.updateTask(any(), anyLong())).thenReturn(true);
        when(repository.saveRun(any())).thenAnswer(invocation -> {
            ValidationRun value = invocation.getArgument(0);
            return value.id() == null
                    ? new ValidationRun(2L, value.taskId(), value.runNo(), value.status(), value.plannedKeys(),
                            value.scannedKeys(), value.comparedKeys(), value.differenceCount(), value.degradedCount(),
                            value.unverifiableCount(), value.inconclusiveCount(), value.startedAt(),
                            value.finishedAt(),
                            value.summaryJson())
                    : value;
        });
        when(redis.countKeys(11L, 0)).thenReturn(1000L);
        when(redis.scanShards(11L, 0)).thenReturn(List.of(new RedisValidationPort.ScanShard("A", ""),
                new RedisValidationPort.ScanShard("B", "")));
        // each shard offers sixty keys but its fifty-key quota must stop it inside the first page
        when(redis.scan(11L, 0, "A", "0", 500)).thenReturn(page("A:1", keys("a", 60)));
        when(redis.scan(11L, 0, "B", "0", 500)).thenReturn(page("B:1", keys("b", 60)));

        service.execute(1);

        ArgumentCaptor<ValidationRun> runs = ArgumentCaptor.forClass(ValidationRun.class);
        verify(repository, atLeast(2)).saveRun(runs.capture());
        ValidationRun finalRun = runs.getAllValues().get(runs.getAllValues().size() - 1);
        assertEquals(100, finalRun.plannedKeys()); // min(dbsize 1000, sampleLimit 100)
        assertEquals(100, finalRun.scannedKeys()); // quota 50 per shard, both capped inside page one
        assertEquals("INCONCLUSIVE", finalRun.status()); // unstubbed inspect -> empty -> inconclusive
        verify(redis, times(2)).scan(eq(11L), eq(0), anyString(), eq("0"), anyInt()); // no second page was needed
    }

    private static String[] keys(String prefix, int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> prefix + i).toArray(String[]::new);
    }

    @Test
    void multiPatternIncludeSurvivesJsonColumnSpacing() {
        ValidationRepository repository = mock(ValidationRepository.class);
        RedisValidationPort redis = mock(RedisValidationPort.class);
        ValidationService service = new ValidationService(repository, mock(ClusterRepository.class),
                mock(LocalJobRunner.class), redis);
        // a JSON column re-serializes arrays as ["a", "b"] — comma-space between elements
        ValidationTask checking = task(ValidationTaskStatus.CHECKING, ValidationStrictness.REPORT,
                "[\"key*\", \"custCertNo*\"]", ValidationSamplingMode.COUNT, 100);
        ValidationTask running = task(ValidationTaskStatus.RUNNING, ValidationStrictness.REPORT,
                "[\"key*\", \"custCertNo*\"]", ValidationSamplingMode.COUNT, 100);
        when(repository.findTask(1)).thenReturn(Optional.of(checking), Optional.of(running), Optional.of(running));
        when(repository.updateTask(any(), anyLong())).thenReturn(true);
        when(repository.saveRun(any())).thenAnswer(invocation -> {
            ValidationRun value = invocation.getArgument(0);
            return value.id() == null
                    ? new ValidationRun(2L, value.taskId(), value.runNo(), value.status(), value.plannedKeys(),
                            value.scannedKeys(), value.comparedKeys(), value.differenceCount(), value.degradedCount(),
                            value.unverifiableCount(), value.inconclusiveCount(), value.startedAt(),
                            value.finishedAt(), value.summaryJson())
                    : value;
        });
        when(redis.countKeys(11L, 0)).thenReturn(1000L);
        when(redis.scanShards(11L, 0)).thenReturn(List.of(new RedisValidationPort.ScanShard("A", "")));
        when(redis.scan(11L, 0, "A", "0", 500)).thenReturn(page("0", "key1", "custCertNo:1", "other1"));
        RedisValidationPort.ValidationValue value = new RedisValidationPort.ValidationValue("string", 10L, -1,
                "digest", "VALUE", null);
        when(redis.inspectBatch(anyLong(), anyInt(), anyList(), any()))
                .thenReturn(List.of(Optional.of(value), Optional.of(value)));

        service.execute(1);

        ArgumentCaptor<ValidationRun> runs = ArgumentCaptor.forClass(ValidationRun.class);
        verify(repository, atLeast(2)).saveRun(runs.capture());
        ValidationRun finalRun = runs.getAllValues().get(runs.getAllValues().size() - 1);
        assertEquals(2, finalRun.scannedKeys()); // both include patterns matched, unrelated key skipped
        assertEquals("PASSED", finalRun.status());
    }

    @Test
    void fullWalkThatMatchedNothingIsNotPassed() {
        ValidationRepository repository = mock(ValidationRepository.class);
        RedisValidationPort redis = mock(RedisValidationPort.class);
        ValidationService service = new ValidationService(repository, mock(ClusterRepository.class),
                mock(LocalJobRunner.class), redis);
        ValidationTask checking = task(ValidationTaskStatus.CHECKING, ValidationStrictness.REPORT, "[\"key*\"]",
                ValidationSamplingMode.FULL, 0);
        ValidationTask running = task(ValidationTaskStatus.RUNNING, ValidationStrictness.REPORT, "[\"key*\"]",
                ValidationSamplingMode.FULL, 0);
        when(repository.findTask(1)).thenReturn(Optional.of(checking), Optional.of(running), Optional.of(running));
        when(repository.updateTask(any(), anyLong())).thenReturn(true);
        when(repository.saveRun(any())).thenAnswer(invocation -> {
            ValidationRun value = invocation.getArgument(0);
            return value.id() == null
                    ? new ValidationRun(2L, value.taskId(), value.runNo(), value.status(), value.plannedKeys(),
                            value.scannedKeys(), value.comparedKeys(), value.differenceCount(), value.degradedCount(),
                            value.unverifiableCount(), value.inconclusiveCount(), value.startedAt(),
                            value.finishedAt(), value.summaryJson())
                    : value;
        });
        when(redis.countKeys(11L, 0)).thenReturn(1000L);
        when(redis.scanShards(11L, 0)).thenReturn(List.of(new RedisValidationPort.ScanShard("A", "")));
        when(redis.scan(11L, 0, "A", "0", 500)).thenReturn(page("0", "zzz1")); // matches no include pattern

        service.execute(1);

        ArgumentCaptor<ValidationRun> runs = ArgumentCaptor.forClass(ValidationRun.class);
        verify(repository, atLeast(2)).saveRun(runs.capture());
        ValidationRun finalRun = runs.getAllValues().get(runs.getAllValues().size() - 1);
        assertEquals(0, finalRun.scannedKeys());
        assertEquals("INCONCLUSIVE", finalRun.status()); // zero matches over a non-empty keyspace cannot pass
    }

    private static RedisValidationPort.ScanPage page(String cursor, String... keys) {
        return new RedisValidationPort.ScanPage(cursor,
                Arrays.stream(keys).map(key -> new RedisValidationPort.ValidationKey(key.getBytes())).toList());
    }

    private static ValidationTask task(ValidationTaskStatus status) {
        return task(status, ValidationStrictness.STRICT);
    }

    private static ValidationTask task(ValidationTaskStatus status, ValidationStrictness strictness) {
        return task(status, strictness, "[]", ValidationSamplingMode.COUNT, 100);
    }

    private static ValidationTask task(ValidationTaskStatus status, ValidationStrictness strictness,
            String includePatternsJson, ValidationSamplingMode samplingMode, int sampleLimit) {
        Instant now = Instant.now();
        return new ValidationTask(1L, "VAL-X", null, 11, 22, 0, 0, strictness, includePatternsJson, "[]", "seed",
                samplingMode, sampleLimit, null, 5, 64L * 1024 * 1024, 8L * 1024 * 1024, 1024 * 1024,
                100_000, status, null, 0, now, now);
    }
}
