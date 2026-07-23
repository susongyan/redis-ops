package io.github.redisops.api.sync;

import io.github.redisops.api.*;
import io.github.redisops.application.IdempotencyService;
import io.github.redisops.application.sync.SyncService;
import io.github.redisops.domain.sync.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
public class SyncController {
    private final SyncService service;private final IdempotencyService idempotency;public SyncController(SyncService service,IdempotencyService idempotency){this.service=service;this.idempotency=idempotency;}
    @PostMapping("/api/v1/sync-tasks") @ResponseStatus(HttpStatus.CREATED) ApiResponse<SyncTask> create(@RequestHeader("Idempotency-Key")String key,@RequestBody SyncTaskRequest b,HttpServletRequest r){String op=op(r);return wrap(idempotency.execute(op,key,"SYNC_TASK_CREATE",b,()->service.create(b.relationId,b.sourceClusterId,b.targetClusterId,b.purpose,b.syncMode,b.toolType,op),x->x.id().toString(),id->service.get(Long.parseLong(id))),r);}
    @GetMapping("/api/v1/sync-tasks") ApiResponse<List<SyncTask>> list(@RequestParam(required=false)Long relationId,HttpServletRequest r){return wrap(service.list(relationId),r);}
    @GetMapping("/api/v1/sync-tasks/{id}") ApiResponse<TaskDetail> get(@PathVariable long id,HttpServletRequest r){return wrap(new TaskDetail(service.get(id),service.events(id)),r);}
    @PostMapping("/api/v1/sync-tasks/{id}/transitions") ApiResponse<SyncTask> transition(@PathVariable long id,@RequestHeader("Idempotency-Key")String key,@RequestHeader("If-Match")long version,@Valid @RequestBody TransitionRequest b,HttpServletRequest r){String op=op(r);return wrap(idempotency.execute(op,key,"SYNC_TASK_TRANSITION",b,()->service.transition(id,version,b.targetStatus,b.lastRpoSeconds,b.error,b.message,op),x->x.id().toString(),rid->service.get(Long.parseLong(rid))),r);}
    @GetMapping("/api/v1/switchovers/{id}") ApiResponse<Switchover> getSwitchover(@PathVariable long id,HttpServletRequest r){return wrap(service.getSwitchover(id),r);}
    @PostMapping("/api/v1/switchovers/{id}/confirm") ApiResponse<Switchover> confirm(@PathVariable long id,@RequestHeader("Idempotency-Key")String key,@RequestHeader("If-Match")long version,HttpServletRequest r){String op=op(r);return wrap(idempotency.execute(op,key,"SWITCHOVER_CONFIRM",id,()->service.confirm(id,version,op),x->x.id().toString(),rid->service.getSwitchover(Long.parseLong(rid))),r);}
    @PostMapping("/api/v1/switchovers/{id}/cancel") ApiResponse<Switchover> cancel(@PathVariable long id,@RequestHeader("Idempotency-Key")String key,@RequestHeader("If-Match")long version,HttpServletRequest r){String op=op(r);return wrap(idempotency.execute(op,key,"SWITCHOVER_CANCEL",id,()->service.cancel(id,version,op),x->x.id().toString(),rid->service.getSwitchover(Long.parseLong(rid))),r);}
    public record SyncTaskRequest(Long relationId,Long sourceClusterId,Long targetClusterId,SyncPurpose purpose,SyncMode syncMode,String toolType){}
    public record TransitionRequest(@NotNull SyncTaskStatus targetStatus,Long lastRpoSeconds,String error,String message){}
    public record TaskDetail(SyncTask task,List<SyncTaskEvent> events){}
    private static String op(HttpServletRequest r){String x=r.getHeader("X-Operator");return x==null?"anonymous":x;}private static <T>ApiResponse<T> wrap(T x,HttpServletRequest r){return ApiResponse.of(x,String.valueOf(r.getAttribute(RequestIdFilter.ATTRIBUTE)));}
}
