package io.github.redisops.api.location;

import io.github.redisops.api.*;
import io.github.redisops.application.IdempotencyService;
import io.github.redisops.application.location.LocationService;
import io.github.redisops.domain.location.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
public class LocationController {
    private final LocationService service;private final IdempotencyService idempotency;
    public LocationController(LocationService service,IdempotencyService idempotency){this.service=service;this.idempotency=idempotency;}
    @PostMapping("/api/v1/regions") @ResponseStatus(HttpStatus.CREATED) ApiResponse<Region> createRegion(@RequestHeader("Idempotency-Key")String key,@Valid @RequestBody RegionRequest b,HttpServletRequest r){String op=op(r);return wrap(idempotency.execute(op,key,"REGION_CREATE",b,()->service.createRegion(b.code,b.name,b.status,b.description,op),x->x.id().toString(),id->service.getRegion(Long.parseLong(id))),r);}
    @GetMapping("/api/v1/regions") ApiResponse<List<Region>> regions(HttpServletRequest r){return wrap(service.regions(),r);}
    @GetMapping("/api/v1/regions/{id}") ApiResponse<Region> region(@PathVariable long id,HttpServletRequest r){return wrap(service.getRegion(id),r);}
    @PutMapping("/api/v1/regions/{id}") ApiResponse<Region> updateRegion(@PathVariable long id,@RequestHeader("If-Match")long version,@RequestHeader("Idempotency-Key")String key,@Valid @RequestBody RegionRequest b,HttpServletRequest r){String operator=op(r);return wrap(idempotency.execute(operator,key,"REGION_UPDATE",new MutationRequest(id,version,b),()->service.updateRegion(id,version,b.code,b.name,b.status,b.description,operator),x->x.id().toString(),resourceId->service.getRegion(Long.parseLong(resourceId))),r);}
    @DeleteMapping("/api/v1/regions/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void deleteRegion(@PathVariable long id,@RequestHeader("If-Match")long version,@RequestHeader("Idempotency-Key")String key,HttpServletRequest r){String operator=op(r);idempotency.executeVoid(operator,key,"REGION_DELETE",new MutationRequest(id,version,null),()->service.deleteRegion(id,version,operator),Long.toString(id));}
    @PostMapping("/api/v1/idcs") @ResponseStatus(HttpStatus.CREATED) ApiResponse<Idc> createIdc(@RequestHeader("Idempotency-Key")String key,@Valid @RequestBody IdcRequest b,HttpServletRequest r){String op=op(r);return wrap(idempotency.execute(op,key,"IDC_CREATE",b,()->service.createIdc(b.code,b.name,b.regionId,b.networkDomain,b.status,b.description,op),x->x.id().toString(),id->service.getIdc(Long.parseLong(id))),r);}
    @GetMapping("/api/v1/idcs") ApiResponse<List<Idc>> idcs(@RequestParam(required=false)Long regionId,HttpServletRequest r){return wrap(service.idcs(regionId),r);}
    @GetMapping("/api/v1/idcs/{id}") ApiResponse<Idc> idc(@PathVariable long id,HttpServletRequest r){return wrap(service.getIdc(id),r);}
    @PutMapping("/api/v1/idcs/{id}") ApiResponse<Idc> updateIdc(@PathVariable long id,@RequestHeader("If-Match")long version,@RequestHeader("Idempotency-Key")String key,@Valid @RequestBody IdcRequest b,HttpServletRequest r){String operator=op(r);return wrap(idempotency.execute(operator,key,"IDC_UPDATE",new MutationRequest(id,version,b),()->service.updateIdc(id,version,b.code,b.name,b.regionId,b.networkDomain,b.status,b.description,operator),x->x.id().toString(),resourceId->service.getIdc(Long.parseLong(resourceId))),r);}
    @DeleteMapping("/api/v1/idcs/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void deleteIdc(@PathVariable long id,@RequestHeader("If-Match")long version,@RequestHeader("Idempotency-Key")String key,HttpServletRequest r){String operator=op(r);idempotency.executeVoid(operator,key,"IDC_DELETE",new MutationRequest(id,version,null),()->service.deleteIdc(id,version,operator),Long.toString(id));}
    public record RegionRequest(@NotBlank String code,@NotBlank String name,ResourceStatus status,String description){}
    public record IdcRequest(@NotBlank String code,@NotBlank String name,@NotNull Long regionId,String networkDomain,ResourceStatus status,String description){}
    private record MutationRequest(long id,long version,Object body){}
    private static String op(HttpServletRequest r){String x=r.getHeader("X-Operator");return x==null?"anonymous":x;}
    private static <T>ApiResponse<T> wrap(T x,HttpServletRequest r){return ApiResponse.of(x,String.valueOf(r.getAttribute(RequestIdFilter.ATTRIBUTE)));}
}
