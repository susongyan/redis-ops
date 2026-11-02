package io.github.susongyan.redisops.worker.runtime;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

/**
 * Read-only snapshot of machine and spool-filesystem health, pulled on demand by the platform
 * worker page via GET /actuator/workerload. Nothing is sampled or stored here — every value is
 * read at request time from JDK MXBeans and the spool data dir's filesystem (including a
 * recursive size sum of the data dir itself), so an idle worker pays nothing for this endpoint
 * existing.
 */
@Component
@Endpoint(id = "workerload")
public class WorkerLoadEndpoint {
    private final WorkerIdentity identity;
    private final String spoolDir;

    public WorkerLoadEndpoint(WorkerIdentity identity,
            @Value("${sync.engine.data-dir:./data/sync}") String spoolDir) {
        this.identity = identity;
        this.spoolDir = spoolDir;
    }

    @ReadOperation
    public Map<String, Object> load() {
        var os = ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        File dir = new File(spoolDir);
        Map<String, Object> load = new LinkedHashMap<>();
        load.put("instanceName", identity.instanceName());
        load.put("cpuSystemPercent", percent(os.getCpuLoad()));
        load.put("cpuProcessPercent", percent(os.getProcessCpuLoad()));
        load.put("heapUsedBytes", heap.getUsed());
        load.put("heapMaxBytes", heap.getMax());
        load.put("systemMemoryTotalBytes", os.getTotalMemorySize());
        load.put("systemMemoryFreeBytes", os.getFreeMemorySize());
        // The spool dir is where encrypted RDB/segment files land — disk pressure here stalls sync.
        load.put("spoolDir", dir.getAbsolutePath());
        load.put("spoolDirBytes", spoolDirBytes());
        load.put("spoolUsableBytes", dir.getUsableSpace());
        load.put("spoolTotalBytes", dir.getTotalSpace());
        return load;
    }

    /**
     * Sums regular-file sizes under the spool dir at request time; a missing dir is 0, matching
     * the filesystem stats above (spool bytes are 0 when the data dir does not exist).
     */
    private long spoolDirBytes() {
        try (var paths = Files.walk(Path.of(spoolDir))) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException error) {
                    return 0;
                }
            }).sum();
        } catch (IOException error) {
            return 0;
        }
    }

    /** MXBean loads are a 0..1 ratio or -1 when not yet available; expose as percent or -1. */
    private static double percent(double ratio) {
        return ratio < 0 ? -1 : BigDecimal.valueOf(ratio * 100).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
