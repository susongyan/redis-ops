package io.github.redisops.application.asset;

import io.github.redisops.common.BusinessException;
import io.github.redisops.common.PageResult;
import io.github.redisops.domain.asset.*;
import io.github.redisops.domain.audit.AuditRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ClusterService {
    private final ClusterRepository clusters;
    private final AuditRepository audits;
    private final io.github.redisops.domain.location.LocationRepository locations;
    private final ClusterSecretRepository secrets;
    private final CredentialSecretProtector secretProtector;

    public ClusterService(ClusterRepository clusters, AuditRepository audits,
                          io.github.redisops.domain.location.LocationRepository locations,
                          ClusterSecretRepository secrets, CredentialSecretProtector secretProtector) {
        this.clusters = clusters;
        this.audits = audits;
        this.locations = locations;
        this.secrets = secrets;
        this.secretProtector = secretProtector;
    }

    @Transactional
    public RedisCluster create(UpsertCluster command, String operator) {
        validateEndpoint(command);
        validateIdc(command.idcId());
        validateAuthentication(command, Optional.empty(), true);
        RedisCluster saved = clusters.save(command.toEntity(null, 0));
        applyAuthentication(saved.id(), command, Optional.empty(), operator);
        audits.append(operator, "CLUSTER_CREATE", "REDIS_CLUSTER", saved.id().toString(), "SUCCESS");
        return saved;
    }

    public RedisCluster get(long id) {
        return clusters.findById(id).orElseThrow(() -> BusinessException.notFound("cluster", id));
    }

    public PageResult<RedisCluster> list(ClusterQuery query) { return clusters.findAll(query); }
    public AuthenticationSummary authentication(long clusterId) {
        get(clusterId);
        return secrets.findByClusterId(clusterId).map(secret -> new AuthenticationSummary(true,
                secret.username(), secret.authType(), secret.passwordConfigured())).orElse(AuthenticationSummary.NONE);
    }

    @Transactional
    public RedisCluster update(long id, long expectedVersion, UpsertCluster command, String operator) {
        get(id);
        validateEndpoint(command);
        validateIdc(command.idcId());
        Optional<RedisClusterSecret> currentSecret=secrets.findByClusterId(id);
        validateAuthentication(command,currentSecret,false);
        RedisCluster replacement = command.toEntity(id, expectedVersion);
        if (!clusters.update(replacement, expectedVersion))
            throw new BusinessException("CONCURRENT_MODIFICATION", "cluster was modified, reload and retry");
        applyAuthentication(id,command,currentSecret,operator);
        audits.append(operator, "CLUSTER_UPDATE", "REDIS_CLUSTER", Long.toString(id), "SUCCESS");
        return get(id);
    }

    @Transactional
    public void delete(long id, long expectedVersion, String operator) {
        get(id);
        if (!clusters.softDelete(id, expectedVersion))
            throw new BusinessException("CONCURRENT_MODIFICATION", "cluster was modified, reload and retry");
        secrets.deleteByClusterId(id);
        audits.append(operator, "CLUSTER_DELETE", "REDIS_CLUSTER", Long.toString(id), "SUCCESS");
    }

    private void validateIdc(Long idcId) {
        if (idcId == null) throw new BusinessException("INVALID_ARGUMENT", "idcId is required");
        var idc=locations.findIdc(idcId).orElseThrow(()->BusinessException.notFound("idc",idcId));
        if (idc.status()!=io.github.redisops.domain.location.ResourceStatus.ACTIVE)
            throw new BusinessException("INVALID_ARGUMENT","IDC is inactive");
    }

    private static void validateEndpoint(UpsertCluster command) {
        RedisEndpointConfiguration.parse(command.mode(), command.endpoint());
    }

    private static void validateAuthentication(UpsertCluster command,Optional<RedisClusterSecret> current,boolean creating){
        if(!command.authEnabled())return;
        boolean passwordMissing=command.password()==null||command.password().isBlank();
        if(passwordMissing&&(creating||current.isEmpty()))
            throw new BusinessException("INVALID_ARGUMENT","password is required when authentication is enabled");
    }

    private void applyAuthentication(long clusterId,UpsertCluster command,Optional<RedisClusterSecret> current,String operator){
        if(!command.authEnabled()){
            if(current.isPresent()){
                secrets.deleteByClusterId(clusterId);
                audits.append(operator,"CLUSTER_CREDENTIAL_CLEAR","REDIS_CLUSTER",Long.toString(clusterId),"SUCCESS");
            }
            return;
        }
        String username=normalize(command.username());
        if(command.password()==null||command.password().isBlank()){
            RedisClusterSecret existing=current.orElseThrow();
            if(!java.util.Objects.equals(existing.username(),username)){
                secrets.save(new RedisClusterSecret(clusterId,existing.secretUuid(),existing.encryptedSecret(),
                        existing.keyId(),username,existing.secretStatus(),existing.version()));
                audits.append(operator,"CLUSTER_CREDENTIAL_UPDATE","REDIS_CLUSTER",Long.toString(clusterId),"SUCCESS");
            }
            return;
        }
        UUID uuid=current.map(RedisClusterSecret::secretUuid).orElseGet(UUID::randomUUID);
        char[] plaintext=command.password().toCharArray();
        try{
            EncryptedSecret encrypted=secretProtector.encrypt(uuid,plaintext);
            secrets.save(new RedisClusterSecret(clusterId,uuid,encrypted.ciphertext(),encrypted.keyId(),username,SecretStatus.ENCRYPTED,
                    current.map(RedisClusterSecret::version).orElse(0L)));
        }finally{Arrays.fill(plaintext,'\0');}
        audits.append(operator,current.isPresent()?"CLUSTER_CREDENTIAL_ROTATE":"CLUSTER_CREDENTIAL_CONFIGURE",
                "REDIS_CLUSTER",Long.toString(clusterId),"SUCCESS");
    }

    private static String normalize(String value){return value==null||value.isBlank()?null:value.trim();}

    public record AuthenticationSummary(boolean authEnabled,String username,String authType,boolean passwordConfigured){
        static final AuthenticationSummary NONE=new AuthenticationSummary(false,null,"NONE",false);
    }

    public record UpsertCluster(String name, String environment, String businessLine, String owner,
                                String opsOwner, String serviceLevel, ClusterMode mode, String redisVersion,
                                String endpoint, Long idcId, boolean authEnabled, String username, String password,
                                ClusterStatus status) {
        RedisCluster toEntity(Long id, long version) {
            return new RedisCluster(id, name, environment, businessLine, owner, opsOwner, serviceLevel,
                    mode, redisVersion, endpoint, idcId, status, version,
                    id == null ? Instant.now() : null, Instant.now());
        }
    }
}
