package io.github.redisops.application.relation;

import io.github.redisops.common.BusinessException;
import io.github.redisops.domain.asset.*;
import io.github.redisops.domain.audit.AuditRepository;
import io.github.redisops.domain.relation.*;
import io.github.redisops.domain.sync.SyncRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;

@Service
public class ClusterRelationService {
    private final ClusterRelationRepository relations;
    private final ClusterRepository clusters;
    private final SyncRepository sync;
    private final AuditRepository audits;
    public ClusterRelationService(ClusterRelationRepository relations, ClusterRepository clusters, SyncRepository sync,
            AuditRepository audits) {
        this.relations = relations;
        this.clusters = clusters;
        this.sync = sync;
        this.audits = audits;
    }
    @Transactional
    public ClusterRelation create(String name, RelationType type, long primaryId, long standbyId, long rpo,
            String description, String operator) {
        require(name, "name");
        if (rpo <= 0)
            throw invalid("desiredRpoSeconds must be positive");
        validateRelationPair(primaryId, standbyId);
        var x = relations.save(new ClusterRelation(null, name, type == null ? RelationType.DISASTER_RECOVERY : type,
                primaryId, standbyId, RelationStatus.ACTIVE, rpo, description, 0, Instant.now(), Instant.now()));
        audit(operator, "CLUSTER_RELATION_CREATE", x.id());
        return x;
    }
    public ClusterRelation get(long id) {
        return relations.find(id).orElseThrow(() -> BusinessException.notFound("cluster relation", id));
    }
    public List<ClusterRelation> list() {
        return relations.findAll();
    }
    @Transactional
    public ClusterRelation update(long id, long version, String name, RelationType type, long primaryId, long standbyId,
            RelationStatus status, long rpo, String description, String operator) {
        var old = get(id);
        if (old.status() == RelationStatus.SWITCHING)
            throw invalid("relation cannot be edited during switchover");
        if (status == RelationStatus.SWITCHING)
            throw invalid("SWITCHING is managed by switchover workflow");
        if ((old.primaryClusterId() != primaryId || old.standbyClusterId() != standbyId)
                && (sync.countActiveTasks(id) > 0 || sync.countActiveSwitchovers(id) > 0))
            throw inUse("active task prevents direction change");
        require(name, "name");
        if (rpo <= 0)
            throw invalid("desiredRpoSeconds must be positive");
        validateRelationPair(primaryId, standbyId);
        var x = new ClusterRelation(id, name, type == null ? RelationType.DISASTER_RECOVERY : type, primaryId,
                standbyId, status == null ? RelationStatus.ACTIVE : status, rpo, description, version, old.createdAt(),
                Instant.now());
        if (!relations.update(x, version))
            concurrent();
        audit(operator, "CLUSTER_RELATION_UPDATE", id);
        return get(id);
    }
    @Transactional
    public void delete(long id, long version, String operator) {
        get(id);
        if (sync.countActiveTasks(id) > 0 || sync.countActiveSwitchovers(id) > 0)
            throw inUse("relation has active sync or switchover tasks");
        if (!relations.delete(id, version))
            concurrent();
        audit(operator, "CLUSTER_RELATION_DELETE", id);
    }
    public void validatePair(long sourceId, long targetId) {
        if (sourceId == targetId)
            throw invalid("source and target clusters must differ");
        var a = cluster(sourceId);
        var b = cluster(targetId);
        if (a.status() != ClusterStatus.ACTIVE || b.status() != ClusterStatus.ACTIVE)
            throw invalid("both clusters must be active");
        if (a.idcId() == null || b.idcId() == null)
            throw invalid("both clusters must have IDC metadata");
        if (a.idcId().equals(b.idcId()))
            throw invalid("clusters must be in different IDCs");
    }
    private void validateRelationPair(long sourceId, long targetId) {
        validatePair(sourceId, targetId);
        var a = cluster(sourceId);
        var b = cluster(targetId);
        String av = majorMinor(a.redisVersion()), bv = majorMinor(b.redisVersion());
        if (av != null && bv != null && !av.equals(bv))
            throw invalid("disaster recovery clusters must use the same Redis major.minor version");
    }
    private RedisCluster cluster(long id) {
        return clusters.findById(id).orElseThrow(() -> BusinessException.notFound("cluster", id));
    }
    private static String majorMinor(String v) {
        if (v == null || v.isBlank())
            return null;
        String[] p = v.split("[.-]");
        return p.length < 2 ? p[0] : p[0] + "." + p[1];
    }
    private void audit(String op, String action, long id) {
        audits.append(op, action, "CLUSTER_RELATION", Long.toString(id), "SUCCESS");
    }
    private static void require(String x, String f) {
        if (x == null || x.isBlank())
            throw invalid(f + " is required");
    }
    private static BusinessException invalid(String m) {
        return new BusinessException("INVALID_ARGUMENT", m);
    }
    private static BusinessException inUse(String m) {
        return new BusinessException("RESOURCE_IN_USE", m);
    }
    private static void concurrent() {
        throw new BusinessException("CONCURRENT_MODIFICATION", "resource was modified, reload and retry");
    }
}
