package io.github.redisops.infrastructure.redis;

import io.github.redisops.common.BusinessException;
import io.github.redisops.domain.asset.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefaultRedisConnectionProfileProviderTest {
    @Test void buildsEncryptedAclClusterProfile() {
        ClusterRepository clusters=mock(ClusterRepository.class);ClusterSecretRepository secrets=mock(ClusterSecretRepository.class);
        CredentialSecretProtector protector=mock(CredentialSecretProtector.class);UUID uuid=UUID.randomUUID();
        RedisClusterSecret secret=new RedisClusterSecret(7,uuid,new byte[]{1,2},"v2","app-user",SecretStatus.ENCRYPTED,0);
        when(clusters.findById(7)).thenReturn(Optional.of(cluster(7,ClusterMode.CLUSTER,"node1:6379,node2:6379")));
        when(secrets.findByClusterId(7)).thenReturn(Optional.of(secret));when(protector.decrypt(uuid,secret.encryptedSecret(),"v2")).thenReturn("secret".toCharArray());

        var provider=new DefaultRedisConnectionProfileProvider(clusters,secrets,protector);
        try(RedisConnectionProfile profile=provider.get(7)){
            assertEquals(java.util.List.of("node1:6379","node2:6379"),profile.seedEndpoints());assertEquals("app-user",profile.username());assertEquals("ACL",profile.authType());assertArrayEquals("secret".toCharArray(),profile.password());
        }
    }

    @Test void missingSecretMeansNoAuthentication(){
        ClusterRepository clusters=mock(ClusterRepository.class);ClusterSecretRepository secrets=mock(ClusterSecretRepository.class);
        when(clusters.findById(8)).thenReturn(Optional.of(cluster(8,ClusterMode.STANDALONE,"redis:6379")));when(secrets.findByClusterId(8)).thenReturn(Optional.empty());
        var provider=new DefaultRedisConnectionProfileProvider(clusters,secrets,mock(CredentialSecretProtector.class));
        try(RedisConnectionProfile profile=provider.get(8)){assertNull(profile.password());assertEquals("NONE",profile.authType());}
    }

    @Test void configuredButInvalidStatusDoesNotFallBackToNoAuthentication(){
        ClusterRepository clusters=mock(ClusterRepository.class);ClusterSecretRepository secrets=mock(ClusterSecretRepository.class);
        when(clusters.findById(9)).thenReturn(Optional.of(cluster(9,ClusterMode.STANDALONE,"redis:6379")));
        when(secrets.findByClusterId(9)).thenReturn(Optional.of(new RedisClusterSecret(9,UUID.randomUUID(),new byte[]{1},"v1",null,SecretStatus.UNCONFIGURED,0)));
        var provider=new DefaultRedisConnectionProfileProvider(clusters,secrets,mock(CredentialSecretProtector.class));
        assertThrows(BusinessException.class,()->provider.get(9));
    }

    private static RedisCluster cluster(long id,ClusterMode mode,String endpoint){return new RedisCluster(id,"cluster-"+id,"prod",null,"owner",null,null,mode,"7.4",endpoint,1L,ClusterStatus.ACTIVE,0,Instant.now(),Instant.now());}
}
