package io.github.redisops.application.sync;

import io.github.redisops.application.relation.ClusterRelationService;
import io.github.redisops.domain.audit.AuditRepository;
import io.github.redisops.domain.relation.*;
import io.github.redisops.domain.sync.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.Instant;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SyncServiceTest {
    @Test void startsControlledSwitchoverOnlyFromCaughtUpTask(){
        SyncRepository sync=mock(SyncRepository.class);ClusterRelationRepository relations=mock(ClusterRelationRepository.class);ClusterRelationService relationService=mock(ClusterRelationService.class);AuditRepository audits=mock(AuditRepository.class);SyncService service=new SyncService(sync,relations,relationService,audits);
        Instant now=Instant.now();ClusterRelation relation=new ClusterRelation(3L,"dr",RelationType.DISASTER_RECOVERY,11,22,RelationStatus.ACTIVE,30,null,4,now,now);SyncTask task=new SyncTask(8L,"SYNC-X",3L,11,22,SyncPurpose.DISASTER_RECOVERY,SyncMode.FULL_AND_INCREMENTAL,SyncTaskStatus.CAUGHT_UP,null,5L,null,2,now,now,null);
        when(relationService.get(3)).thenReturn(relation);when(sync.findLatestTask(3)).thenReturn(Optional.of(task));when(sync.updateTask(any(),eq(2L),eq("operator"),anyString())).thenReturn(true);when(relations.update(any(),eq(4L))).thenReturn(true);when(sync.saveSwitchover(any())).thenAnswer(i->{Switchover x=i.getArgument(0);return new Switchover(9L,x.relationId(),x.oldPrimaryClusterId(),x.oldStandbyClusterId(),x.stoppedTaskId(),null,x.status(),x.operator(),null,0,now,now,null);});
        Switchover result=service.startSwitchover(3,"operator");assertEquals(SwitchoverStatus.WAITING_EXTERNAL_SWITCH,result.status());ArgumentCaptor<SyncTask> stopped=ArgumentCaptor.forClass(SyncTask.class);verify(sync).updateTask(stopped.capture(),eq(2L),eq("operator"),anyString());assertEquals(SyncTaskStatus.FINISHED,stopped.getValue().status());ArgumentCaptor<ClusterRelation> switching=ArgumentCaptor.forClass(ClusterRelation.class);verify(relations).update(switching.capture(),eq(4L));assertEquals(RelationStatus.SWITCHING,switching.getValue().status());
    }
}
