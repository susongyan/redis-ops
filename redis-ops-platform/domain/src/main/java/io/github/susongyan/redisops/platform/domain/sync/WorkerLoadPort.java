package io.github.susongyan.redisops.platform.domain.sync;

/** Outbound adapter: reads the live load snapshot from a worker's actuator endpoint. */
public interface WorkerLoadPort {
    WorkerLoad fetch(String workerIp, int workerPort);
}
