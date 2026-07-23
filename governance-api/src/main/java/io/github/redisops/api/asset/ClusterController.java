package io.github.redisops.api.asset;

import io.github.redisops.api.*;
import io.github.redisops.application.asset.*;
import io.github.redisops.application.IdempotencyService;
import io.github.redisops.common.PageResult;
import io.github.redisops.domain.asset.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import io.github.redisops.application.location.LocationService;
import io.github.redisops.domain.location.Idc;

@RestController
@RequestMapping("/api/v1/clusters")
public class ClusterController {
    private final ClusterService clusters; private final AssetService assets;private final IdempotencyService idempotency;private final LocationService locations;private final RedisConnectionTestService connectionTests;
    public ClusterController(ClusterService clusters,AssetService assets,IdempotencyService idempotency,LocationService locations,RedisConnectionTestService connectionTests){this.clusters=clusters;this.assets=assets;this.idempotency=idempotency;this.locations=locations;this.connectionTests=connectionTests;}

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RedisCluster> create(@RequestHeader("Idempotency-Key")String key,@Valid @RequestBody ClusterRequest body,HttpServletRequest request){
        String operator=operator(request);RedisCluster result=idempotency.execute(operator,key,"CLUSTER_CREATE",body,
                ()->clusters.create(body.command(),operator),c->c.id().toString(),id->clusters.get(Long.parseLong(id)));
        return wrap(result,request);
    }
    @GetMapping("/{id}") public ApiResponse<ClusterDetail> get(@PathVariable long id,HttpServletRequest request){
        RedisCluster cluster=clusters.get(id);
        return wrap(new ClusterDetail(cluster,cluster.idcId()==null?null:locations.getIdc(cluster.idcId()),
                clusters.authentication(id),assets.nodes(id),assets.bindings(id)),request);
    }
    @GetMapping public ApiResponse<PageResult<RedisCluster>> list(@RequestParam(required=false)String environment,
      @RequestParam(required=false)String businessLine,@RequestParam(required=false)String owner,
      @RequestParam(required=false)ClusterStatus status,@RequestParam(defaultValue="1")int page,
      @RequestParam(defaultValue="20")int size,HttpServletRequest request){
        return wrap(clusters.list(new ClusterQuery(environment,businessLine,owner,status,page,size)),request);
    }
    @PutMapping("/{id}") public ApiResponse<RedisCluster> update(@PathVariable long id,@RequestHeader("If-Match")long version,
      @RequestHeader("Idempotency-Key")String key,@Valid @RequestBody ClusterRequest body,HttpServletRequest request){
        String operator=operator(request);return wrap(idempotency.execute(operator,key,"CLUSTER_UPDATE",
                new MutationRequest(id,version,body),()->clusters.update(id,version,body.command(),operator),
                c->c.id().toString(),resourceId->clusters.get(Long.parseLong(resourceId))),request);}
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id,@RequestHeader("If-Match")long version,
                       @RequestHeader("Idempotency-Key")String key,HttpServletRequest request){
        String operator=operator(request);idempotency.executeVoid(operator,key,"CLUSTER_DELETE",
                new MutationRequest(id,version,null),()->clusters.delete(id,version,operator),Long.toString(id));}
    @PostMapping("/connection-tests")
    public ApiResponse<RedisConnectionTestResult> testConnection(@Valid @RequestBody ConnectionTestRequest body,HttpServletRequest request){
        return wrap(connectionTests.test(body.clusterId(),body.mode(),body.endpoint(),body.authEnabled(),
                body.username(),body.password()),request);
    }
    @PostMapping("/{id}/discoveries") @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<AssetService.DiscoverySubmission> discover(@PathVariable long id,@RequestHeader("Idempotency-Key")String key,HttpServletRequest request){
        String operator=operator(request);return wrap(idempotency.execute(operator,key,"CLUSTER_DISCOVERY",id,
                ()->assets.enqueueDiscovery(id,operator,key),s->s.job().id().toString(),jobId->assets.getSubmission(Long.parseLong(jobId))),request);}
    @GetMapping("/{id}/discoveries") public ApiResponse<List<DiscoveryRun>> discoveries(@PathVariable long id,HttpServletRequest request){return wrap(assets.discoveries(id),request);}
    @GetMapping("/{id}/nodes") public ApiResponse<List<RedisNode>> nodes(@PathVariable long id,HttpServletRequest request){return wrap(assets.nodes(id),request);}

    public record ClusterRequest(@NotBlank String name,@NotBlank String environment,String businessLine,
      @NotBlank String owner,String opsOwner,String serviceLevel,@NotNull ClusterMode mode,String redisVersion,
      @NotBlank String endpoint,@NotNull Long idcId,boolean authEnabled,String username,String password,ClusterStatus status){
        ClusterService.UpsertCluster command(){return new ClusterService.UpsertCluster(name,environment,businessLine,owner,opsOwner,serviceLevel,mode,redisVersion,endpoint,idcId,authEnabled,username,password,status);}
    }
    public record ClusterDetail(RedisCluster cluster,Idc location,ClusterService.AuthenticationSummary authentication,List<RedisNode> nodes,List<ApplicationBinding> bindings){}
    public record ConnectionTestRequest(Long clusterId,@NotNull ClusterMode mode,@NotBlank String endpoint,
                                        boolean authEnabled,String username,String password){}
    private record MutationRequest(long id,long version,Object body){}
    private static String operator(HttpServletRequest r){String v=r.getHeader("X-Operator");return v==null?"anonymous":v;}
    private static <T>ApiResponse<T> wrap(T value,HttpServletRequest r){return ApiResponse.of(value,String.valueOf(r.getAttribute(RequestIdFilter.ATTRIBUTE)));}
}
