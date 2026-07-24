package io.github.redisops.api.relation;

import io.github.redisops.api.*;
import io.github.redisops.application.IdempotencyService;
import io.github.redisops.application.relation.ClusterRelationService;
import io.github.redisops.application.sync.SyncService;
import io.github.redisops.domain.relation.*;
import io.github.redisops.domain.sync.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/cluster-relations")
public class ClusterRelationController {
    private final ClusterRelationService relations;
    private final SyncService sync;
    private final IdempotencyService idempotency;
    public ClusterRelationController(ClusterRelationService relations, SyncService sync,
            IdempotencyService idempotency) {
        this.relations = relations;
        this.sync = sync;
        this.idempotency = idempotency;
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<ClusterRelation> create(@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody RelationRequest b, HttpServletRequest r) {
        String op = op(r);
        return wrap(idempotency.execute(op, key, "CLUSTER_RELATION_CREATE", b,
                () -> relations.create(b.name, b.relationType, b.primaryClusterId, b.standbyClusterId,
                        b.desiredRpoSeconds, b.description, op),
                x -> x.id().toString(), id -> relations.get(Long.parseLong(id))), r);
    }
    @GetMapping
    ApiResponse<List<ClusterRelation>> list(HttpServletRequest r) {
        return wrap(relations.list(), r);
    }
    @GetMapping("/{id}")
    ApiResponse<RelationDetail> get(@PathVariable long id, HttpServletRequest r) {
        return wrap(new RelationDetail(relations.get(id), sync.list(id), sync.switchovers(id)), r);
    }
    @PutMapping("/{id}")
    ApiResponse<ClusterRelation> update(@PathVariable long id, @RequestHeader("If-Match") long version,
            @Valid @RequestBody RelationRequest b, HttpServletRequest r) {
        return wrap(relations.update(id, version, b.name, b.relationType, b.primaryClusterId, b.standbyClusterId,
                b.status, b.desiredRpoSeconds, b.description, op(r)), r);
    }
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long id, @RequestHeader("If-Match") long version, HttpServletRequest r) {
        relations.delete(id, version, op(r));
    }
    @PostMapping("/{id}/switchovers")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<Switchover> switchover(@PathVariable long id, @RequestHeader("Idempotency-Key") String key,
            HttpServletRequest r) {
        String op = op(r);
        return wrap(idempotency.execute(op, key, "SWITCHOVER_START", id, () -> sync.startSwitchover(id, op),
                x -> x.id().toString(), sid -> sync.getSwitchover(Long.parseLong(sid))), r);
    }
    public record RelationRequest(@NotBlank String name, RelationType relationType, @NotNull Long primaryClusterId,
            @NotNull Long standbyClusterId, RelationStatus status, @Positive long desiredRpoSeconds,
            String description) {
    }
    public record RelationDetail(ClusterRelation relation, List<SyncTask> tasks, List<Switchover> switchovers) {
    }
    private static String op(HttpServletRequest r) {
        String x = r.getHeader("X-Operator");
        return x == null ? "anonymous" : x;
    }
    private static <T> ApiResponse<T> wrap(T x, HttpServletRequest r) {
        return ApiResponse.of(x, String.valueOf(r.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
