package io.github.susongyan.redisops.platform.application.asset;
import io.github.susongyan.redisops.platform.application.LocalJobRunner;
import io.github.susongyan.redisops.platform.application.audit.AuditDetails;
import java.util.Map;

import io.github.susongyan.redisops.platform.common.BusinessException;
import io.github.susongyan.redisops.platform.domain.asset.*;
import io.github.susongyan.redisops.platform.domain.audit.AuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class AssetService {
    private static final Logger log = LoggerFactory.getLogger(AssetService.class);
    private static final Duration STALE_RUN = Duration.ofMinutes(10);
    private final AssetRepository assets;
    private final ClusterRepository clusters;
    private final TopologyDiscoveryPort discovery;
    private final AuditRepository audits;
    private final LocalJobRunner runner;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AssetService(AssetRepository assets, ClusterRepository clusters,
            TopologyDiscoveryPort discovery, AuditRepository audits, LocalJobRunner runner) {
        this.assets = assets;
        this.clusters = clusters;
        this.discovery = discovery;
        this.audits = audits;
        this.runner = runner;
    }

    @Transactional
    public ManagedApplication createApplication(String code, String name, String owner,
            String businessLine, String operator) {
        if (code == null || code.isBlank() || name == null || name.isBlank())
            throw new BusinessException("INVALID_ARGUMENT", "code and name are required");
        ManagedApplication saved = assets.saveApplication(
                new ManagedApplication(null, code, name, owner, businessLine, "ACTIVE", 0));
        audits.append(operator, "APPLICATION_CREATE", "APPLICATION", saved.id().toString(), "SUCCESS",
                AuditDetails.change("新增应用：" + saved.name(), Map.of(), AuditDetails.application(saved), null));
        return saved;
    }

    public List<ManagedApplication> listApplications() {
        return assets.findApplications();
    }
    public ManagedApplication getApplication(long id) {
        return assets.findApplication(id).orElseThrow(() -> BusinessException.notFound("application", id));
    }
    @Transactional
    public ManagedApplication updateApplication(long id, long version, String code, String name, String owner,
            String businessLine, String status, String operator) {
        var previous = getApplication(id);
        ManagedApplication replacement = new ManagedApplication(id, code, name, owner, businessLine,
                status == null ? "ACTIVE" : status, version);
        if (!assets.updateApplication(replacement, version))
            throw concurrent("application");
        audits.append(operator, "APPLICATION_UPDATE", "APPLICATION", Long.toString(id), "SUCCESS",
                AuditDetails.change("修改应用：" + replacement.name(), AuditDetails.application(previous),
                        AuditDetails.application(replacement), null));
        return getApplication(id);
    }
    @Transactional
    public void deleteApplication(long id, long version, String operator) {
        var previous = getApplication(id);
        if (!assets.deleteApplication(id, version))
            throw concurrent("application");
        audits.append(operator, "APPLICATION_DELETE", "APPLICATION", Long.toString(id), "SUCCESS",
                AuditDetails.change("删除应用：" + previous.name(), AuditDetails.application(previous), Map.of(), null));
    }
    public List<ApplicationBinding> applicationBindings(long id) {
        getApplication(id);
        return assets.findBindingsByApplication(id);
    }

    @Transactional
    public void bind(long applicationId, ApplicationBinding binding, String operator) {
        var previous = assets.findBindingsByApplication(applicationId).stream()
                .filter(x -> x.clusterId() == binding.clusterId()).findFirst().orElse(null);
        assets.findApplication(applicationId)
                .orElseThrow(() -> BusinessException.notFound("application", applicationId));
        clusters.findById(binding.clusterId())
                .orElseThrow(() -> BusinessException.notFound("cluster", binding.clusterId()));
        String poolConfig = binding.poolConfig() == null || binding.poolConfig().isBlank()
                ? "{}"
                : binding.poolConfig();
        try {
            if (!objectMapper.readTree(poolConfig).isObject())
                throw new IllegalArgumentException();
        } catch (Exception e) {
            throw new BusinessException("INVALID_POOL_CONFIG", "poolConfig must be a valid JSON object");
        }
        assets.bind(new ApplicationBinding(applicationId, binding.clusterId(), binding.clientType(),
                binding.clientVersion(), poolConfig));
        var next = bindingDetails(binding);
        if (previous == null || !java.util.Objects.equals(previous.poolConfig(), poolConfig))
            next.put("连接池配置", "已配置或变更（内容不记录）");
        audits.append(operator, "APPLICATION_BIND", "REDIS_CLUSTER", Long.toString(binding.clusterId()), "SUCCESS",
                AuditDetails.change("应用关联集群：应用 " + applicationId + " / 集群 " + binding.clusterId(),
                        bindingDetails(previous), next, null));
    }

    @Transactional
    public void unbind(long applicationId, long clusterId, String operator) {
        var previous = assets.findBindingsByApplication(applicationId).stream()
                .filter(x -> x.clusterId() == clusterId).findFirst().orElse(null);
        assets.unbind(applicationId, clusterId);
        audits.append(operator, "APPLICATION_UNBIND", "REDIS_CLUSTER", Long.toString(clusterId), "SUCCESS",
                AuditDetails.change("解除关联：应用 " + applicationId + " / 集群 " + clusterId,
                        bindingDetails(previous), Map.of(), null));
    }

    private static Map<String, Object> bindingDetails(ApplicationBinding b) {
        return b == null
                ? Map.of()
                : AuditDetails.fields("应用 ID", b.applicationId(), "集群 ID", b.clusterId(),
                        "客户端类型", b.clientType(), "客户端版本", b.clientVersion());
    }

    public List<ApplicationBinding> bindings(long clusterId) {
        return assets.findBindingsByCluster(clusterId);
    }
    public List<RedisNode> nodes(long clusterId) {
        return assets.findNodes(clusterId);
    }
    public List<DiscoveryRun> discoveries(long clusterId) {
        return assets.findDiscoveries(clusterId);
    }

    /** Discovery runs on this instance; the returned run is what callers poll for progress. */
    public DiscoverySubmission submitDiscovery(long clusterId, String operator, String idempotencyKey) {
        clusters.findById(clusterId).orElseThrow(() -> BusinessException.notFound("cluster", clusterId));
        runner.checkCapacity("CLUSTER_DISCOVERY");
        DiscoveryRun run = assets.startDiscovery(clusterId);
        runner.submit("CLUSTER_DISCOVERY", () -> executeDiscovery(run.id(), clusterId, operator));
        audits.append(operator, "CLUSTER_DISCOVERY_SUBMIT", "REDIS_CLUSTER", Long.toString(clusterId), "SUCCESS");
        return new DiscoverySubmission(run);
    }
    public DiscoverySubmission getSubmission(long runId) {
        DiscoveryRun run = assets.findDiscovery(runId)
                .orElseThrow(() -> BusinessException.notFound("discovery", runId));
        return new DiscoverySubmission(run);
    }
    public void executeDiscovery(long runId, long clusterId, String operator) {
        assets.restartDiscovery(runId);
        assets.touchDiscovery(runId);
        RedisCluster cluster = clusters.findById(clusterId)
                .orElseThrow(() -> BusinessException.notFound("cluster", clusterId));
        try {
            List<RedisNode> nodes = discovery.discover(cluster);
            assets.completeDiscovery(runId, clusterId, nodes);
            audits.append(operator, "CLUSTER_DISCOVER", "REDIS_CLUSTER", Long.toString(clusterId), "SUCCESS");
        } catch (RuntimeException exception) {
            assets.failDiscovery(runId, abbreviate(exception.getMessage()));
            audits.append(operator, "CLUSTER_DISCOVER", "REDIS_CLUSTER", Long.toString(clusterId), "FAILED");
            log.warn("discovery run {} for cluster {} failed", runId, clusterId, exception);
        }
    }
    /** Fails discovery runs whose heartbeat went stale — their executing instance died. */
    public void recoverInterrupted() {
        for (DiscoveryRun run : assets.findStaleDiscoveries(Instant.now().minus(STALE_RUN))) {
            assets.failDiscovery(run.id(), "interrupted: executor lost");
            log.warn("recovered stale discovery run {} of cluster {}", run.id(), run.clusterId());
        }
    }

    public record DiscoverySubmission(DiscoveryRun discovery) {
    }
    private static BusinessException concurrent(String resource) {
        return new BusinessException("CONCURRENT_MODIFICATION", resource + " was modified, reload and retry");
    }

    private static String abbreviate(String message) {
        if (message == null)
            return "unknown error";
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
