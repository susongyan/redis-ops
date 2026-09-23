package io.github.susongyan.redisops.platform.application.sync;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.susongyan.redisops.platform.application.relation.ClusterRelationService;
import io.github.susongyan.redisops.platform.common.BusinessException;
import io.github.susongyan.redisops.platform.domain.asset.*;
import io.github.susongyan.redisops.platform.domain.audit.AuditRepository;
import io.github.susongyan.redisops.platform.domain.job.JobRepository;
import io.github.susongyan.redisops.platform.domain.relation.*;
import io.github.susongyan.redisops.platform.domain.sync.*;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SyncIdcValidationTest {
    private final ClusterRepository clusters = mock(ClusterRepository.class);
    private final ClusterRelationRepository relations = mock(ClusterRelationRepository.class);
    private final SyncRepository sync = mock(SyncRepository.class);
    private final AuditRepository audits = mock(AuditRepository.class);
    private final ClusterRelationService relationService = new ClusterRelationService(relations, clusters, sync,
            audits);
    private final SyncService service = new SyncService(sync, relations, relationService, clusters,
            mock(JobRepository.class), audits, new ObjectMapper());

    private void clusters(Long sourceIdc, Long targetIdc, ClusterStatus targetStatus) {
        when(clusters.findById(1)).thenReturn(Optional.of(cluster(1, sourceIdc, ClusterStatus.ACTIVE)));
        when(clusters.findById(2)).thenReturn(Optional.of(cluster(2, targetIdc, targetStatus)));
        var result = new SyncTask(9L, "SYNC-IDC", null, 1, 2, SyncPurpose.ADHOC,
                SyncMode.FULL_AND_INCREMENTAL, SyncTaskStatus.CREATED, "NATIVE_JAVA", 0, 0, "[\"*\"]", "[]",
                50_000, 100 * 1024 * 1024L, 50L * 1024 * 1024 * 1024, 4, 100, null, true,
                "initial fence", null, "epoch", 2L, null, 0, Instant.now(), Instant.now(), null);
        when(sync.saveTask(any(), anyString(), anyString())).thenReturn(result);
        when(relations.find(3)).thenReturn(Optional.of(new ClusterRelation(3L, "dr",
                RelationType.DISASTER_RECOVERY, 1, 2, RelationStatus.ACTIVE, 30, null, 0, Instant.now(),
                Instant.now())));
    }

    private SyncTask create(Long relation, long target) {
        return service.create(relation, 1L, target, SyncPurpose.ADHOC, SyncMode.FULL_AND_INCREMENTAL,
                0, 0, null, null, null, null, null, null, null, "tester");
    }

    @Test
    void standaloneTasksAcceptSameMissingAndDifferentIdcs() {
        for (Long[] idcs : new Long[][]{{10L, 10L}, {null, null}, {10L, null}, {null, 20L}, {10L, 20L}}) {
            clusters(idcs[0], idcs[1], ClusterStatus.ACTIVE);
            assertEquals(9L, create(null, 2).id());
        }
    }

    @Test
    void relationTasksRecheckCurrentIdcs() {
        for (Long[] idcs : new Long[][]{{10L, 10L}, {null, null}, {10L, null}, {null, 20L}}) {
            clusters(idcs[0], idcs[1], ClusterStatus.ACTIVE);
            assertThrows(BusinessException.class, () -> create(3L, 2));
        }
        verify(sync, never()).saveTask(any(), anyString(), anyString());
        clusters(10L, 20L, ClusterStatus.ACTIVE);
        assertEquals(9L, create(3L, 2).id());
    }

    @Test
    void ordinaryTasksStillRejectSameInactiveAndMissingClusters() {
        clusters(10L, 10L, ClusterStatus.INACTIVE);
        assertThrows(BusinessException.class, () -> create(null, 1));
        assertThrows(BusinessException.class, () -> create(null, 2));
        assertThrows(BusinessException.class, () -> create(null, 999));
        verify(sync, never()).saveTask(any(), anyString(), anyString());
    }

    @Test
    void relationCreateAndUpdateStillRequireCrossIdc() {
        clusters(10L, 10L, ClusterStatus.ACTIVE);
        assertThrows(BusinessException.class, () -> relationService.create("dr", null, 1, 2, 30, null, "tester"));
        assertThrows(BusinessException.class, () -> relationService.update(3, 0, "dr", null, 1, 2,
                RelationStatus.ACTIVE, 30, null, "tester"));
    }

    private RedisCluster cluster(long id, Long idc, ClusterStatus status) {
        return new RedisCluster(id, "cluster-" + id, "test", null, "owner", null, null,
                ClusterMode.STANDALONE, "7.4", "redis-" + id + ":6379", idc, status, 0, Instant.now(), Instant.now());
    }
}
