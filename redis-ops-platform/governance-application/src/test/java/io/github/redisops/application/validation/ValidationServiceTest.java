package io.github.redisops.application.validation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.redisops.domain.asset.ClusterRepository;
import io.github.redisops.domain.job.JobRepository;
import io.github.redisops.domain.validation.*;
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
                mock(JobRepository.class), redis);
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
        when(redis.inspect(anyLong(), anyInt(), eq(key), any())).thenReturn(Optional.of(value));

        service.execute(1);

        ArgumentCaptor<ValidationTask> captured = ArgumentCaptor.forClass(ValidationTask.class);
        verify(repository, atLeast(2)).updateTask(captured.capture(), anyLong());
        assertEquals(ValidationTaskStatus.INCONCLUSIVE,
                captured.getAllValues().get(captured.getAllValues().size() - 1).status());
        verify(repository).saveDifferences(argThat(items -> items.size() == 1
                && items.get(0).differenceType() == ValidationDifferenceType.LARGE_KEY_DEGRADED));
    }

    private static ValidationTask task(ValidationTaskStatus status) {
        Instant now = Instant.now();
        return new ValidationTask(1L, "VAL-X", null, 11, 22, 0, 0, ValidationStrictness.STRICT, "[]", "[]", "seed",
                ValidationSamplingMode.COUNT, 100, null, 5, 64L * 1024 * 1024, 8L * 1024 * 1024, 1024 * 1024,
                100_000, status, null, 0, now, now);
    }
}
