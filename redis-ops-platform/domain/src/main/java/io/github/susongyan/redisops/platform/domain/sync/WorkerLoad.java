package io.github.susongyan.redisops.platform.domain.sync;

/**
 * On-demand machine snapshot fetched live from a worker's /actuator/workerload endpoint. Never
 * persisted — the platform page shows the freshest read only. CPU percents are -1 when the worker
 * JVM cannot sample them yet; spool bytes are 0 when the data dir does not exist. spoolDirBytes
 * is the recursive size of the data dir itself (-1 when an older worker did not report it).
 */
public record WorkerLoad(String instanceName, double cpuSystemPercent, double cpuProcessPercent,
        long heapUsedBytes, long heapMaxBytes, long systemMemoryTotalBytes, long systemMemoryFreeBytes,
        String spoolDir, long spoolDirBytes, long spoolUsableBytes, long spoolTotalBytes) {
}
