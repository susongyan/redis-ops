package io.github.susongyan.redisops.worker.runtime;

import io.github.susongyan.redisops.worker.persistence.WorkerSyncStatePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deletes this worker's local spool directory ({@code <data-dir>/<taskId>/}) for tasks that no longer
 * need it: terminal tasks (FINISHED/CANCELLED/FAILED) whose last task-row update is older than
 * {@code sync.engine.spool-retention-seconds}, or that the platform explicitly flagged through
 * {@code sync_task.spool_cleanup_requested_at} (manual "clean up spool" button). Runs on every worker
 * and only ever touches directories on its own disk, so no cross-instance coordination is needed:
 * each instance reclaims the copies it happens to hold, and the flag stays set harmlessly once no
 * directory is left anywhere. Tasks currently managed by a live runner are never touched.
 */
@Component
@ConditionalOnProperty(name = "sync.engine.enabled", havingValue = "true", matchIfMissing = true)
public class SpoolRetentionWorker {
    private static final Logger log = LoggerFactory.getLogger(SpoolRetentionWorker.class);
    private final WorkerSyncStatePort state;
    private final NativeSyncRunnerManager runners;
    private final Path dataDirectory;
    private final long retentionSeconds;

    public SpoolRetentionWorker(WorkerSyncStatePort state, NativeSyncRunnerManager runners,
            @Value("${sync.engine.data-dir:./data/sync}") Path dataDirectory,
            @Value("${sync.engine.spool-retention-seconds:86400}") long retentionSeconds) {
        this.state = state;
        this.runners = runners;
        this.dataDirectory = dataDirectory;
        this.retentionSeconds = retentionSeconds;
    }

    @Scheduled(initialDelayString = "${sync.engine.spool-sweep-initial-delay-ms:60000}", fixedDelayString = "${sync.engine.spool-sweep-interval-ms:30000}")
    public void sweep() {
        List<Long> candidates = localTaskIds();
        if (candidates.isEmpty())
            return;
        for (long taskId : state.findSpoolPurgeTaskIds(candidates, retentionSeconds)) {
            Path directory = dataDirectory.resolve(String.valueOf(taskId));
            long freed = deleteDirectory(directory);
            state.zeroRuntimeSpoolBytes(taskId);
            // Report only when something actually happened: a directory stuck half-deleted (e.g.
            // undeletable file) must not emit a "bytes=0" event on every sweep.
            if (freed > 0 || !Files.exists(directory))
                state.appendEngineEvent(taskId, "SPOOL_PURGED bytes=" + freed, "sync:spool-retention");
            log.info("purged local spool of sync task {} ({} bytes freed)", taskId, freed);
        }
    }

    private List<Long> localTaskIds() {
        List<Long> ids = new ArrayList<>();
        if (!Files.isDirectory(dataDirectory))
            return ids;
        try (var entries = Files.list(dataDirectory)) {
            entries.forEach(entry -> {
                String name = entry.getFileName().toString();
                if (!Files.isDirectory(entry) || !name.chars().allMatch(Character::isDigit))
                    return;
                long taskId = Long.parseLong(name);
                if (!runners.isManaged(taskId))
                    ids.add(taskId);
            });
        } catch (IOException | UncheckedIOException error) {
            log.warn("cannot list spool directory {}: {}", dataDirectory, error.getMessage());
        }
        return ids;
    }

    /**
     * Recursively deletes the directory and returns the bytes reclaimed; a partial failure keeps
     * the remainder for the next sweep (the task stays in the purge set while anything is left).
     */
    private long deleteDirectory(Path directory) {
        if (!Files.exists(directory))
            return 0;
        long freed = 0;
        try (var paths = Files.walk(directory)) {
            List<Path> ordered = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path path : ordered) {
                try {
                    if (Files.isRegularFile(path))
                        freed += Files.size(path);
                    Files.delete(path);
                } catch (IOException error) {
                    log.warn("cannot delete spool file {}: {}", path, error.getMessage());
                }
            }
        } catch (IOException error) {
            log.warn("cannot walk spool directory {}: {}", directory, error.getMessage());
        }
        return freed;
    }
}
