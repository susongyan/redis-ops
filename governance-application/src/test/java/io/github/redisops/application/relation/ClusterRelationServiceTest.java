package io.github.redisops.application.relation;

import io.github.redisops.common.BusinessException;
import io.github.redisops.domain.asset.*;
import io.github.redisops.domain.audit.AuditRepository;
import io.github.redisops.domain.relation.*;
import io.github.redisops.domain.sync.SyncRepository;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ClusterRelationServiceTest {
    private final ClusterRelationRepository relations = mock(ClusterRelationRepository.class);
    private final ClusterRepository clusters = mock(ClusterRepository.class);
    private final SyncRepository sync = mock(SyncRepository.class);
    private final AuditRepository audits = mock(AuditRepository.class);
    private final ClusterRelationService service = new ClusterRelationService(relations, clusters, sync, audits);
    @Test
    void rejectsClustersInSameIdc() {
        when(clusters.findById(1)).thenReturn(Optional.of(cluster(1, 10)));
        when(clusters.findById(2)).thenReturn(Optional.of(cluster(2, 10)));
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.create("dr", RelationType.DISASTER_RECOVERY, 1, 2, 30, null, "tester"));
        assertTrue(e.getMessage().contains("different IDCs"));
    }
    @Test
    void createsCrossIdcRelation() {
        when(clusters.findById(1)).thenReturn(Optional.of(cluster(1, 10)));
        when(clusters.findById(2)).thenReturn(Optional.of(cluster(2, 20)));
        when(relations.save(any())).thenAnswer(i -> {
            ClusterRelation x = i.getArgument(0);
            return new ClusterRelation(7L, x.name(), x.relationType(), x.primaryClusterId(), x.standbyClusterId(),
                    x.status(), x.desiredRpoSeconds(), x.description(), 0, x.createdAt(), x.updatedAt());
        });
        assertEquals(7L, service.create("dr", null, 1, 2, 30, null, "tester").id());
    }
    private static RedisCluster cluster(long id, long idc) {
        return new RedisCluster(id, "c" + id, "prod", null, "owner", null, null, ClusterMode.CLUSTER, "7.4",
                "redis:6379", idc, ClusterStatus.ACTIVE, 0, Instant.now(), Instant.now());
    }
}
