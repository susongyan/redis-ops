package io.github.susongyan.redisops.worker.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerLoadEndpointTest {
    @Test
    void reportsMachineAndSpoolFilesystemSnapshot(@TempDir java.nio.file.Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("7/master-1-6379"));
        Files.write(tempDir.resolve("7/master-1-6379/commands-00000000.pseg"), new byte[128]);
        Files.write(tempDir.resolve("7/stray.tmp"), new byte[64]);
        WorkerLoadEndpoint endpoint = new WorkerLoadEndpoint(new WorkerIdentity("worker-1", "", 8081),
                tempDir.toString());

        Map<String, Object> load = endpoint.load();

        assertEquals("worker-1", load.get("instanceName"));
        assertEquals(tempDir.toAbsolutePath().toString(), load.get("spoolDir"));
        // The dir size is a recursive sum of regular files only.
        assertEquals(192L, load.get("spoolDirBytes"));
        // The temp dir exists on a real filesystem, so the spool stats must be positive.
        assertTrue((long) load.get("spoolTotalBytes") > 0);
        assertTrue((long) load.get("spoolUsableBytes") > 0);
        assertTrue((long) load.get("spoolUsableBytes") <= (long) load.get("spoolTotalBytes"));
        // CPU load may legitimately be -1 right after boot; any value must be a sane percent.
        assertTrue((double) load.get("cpuSystemPercent") >= -1);
        assertTrue((double) load.get("cpuProcessPercent") >= -1);
        assertTrue((long) load.get("heapUsedBytes") > 0);
        assertTrue((long) load.get("systemMemoryTotalBytes") > 0);
    }

    @Test
    void toleratesMissingSpoolDirectoryWithZeroStats() {
        java.nio.file.Path missing = java.nio.file.Path.of("no-such-data-dir");
        assertFalse(Files.exists(missing));

        Map<String, Object> load = new WorkerLoadEndpoint(new WorkerIdentity("worker-1", "", 8081),
                missing.toString()).load();

        assertEquals(0L, load.get("spoolDirBytes"));
        assertEquals(0L, load.get("spoolTotalBytes"));
        assertEquals(0L, load.get("spoolUsableBytes"));
        assertEquals(missing.toAbsolutePath().toString(), load.get("spoolDir"));
    }
}
