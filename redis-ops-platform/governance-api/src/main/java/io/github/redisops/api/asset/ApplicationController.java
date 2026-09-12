package io.github.redisops.api.asset;

import io.github.redisops.api.*;
import io.github.redisops.application.asset.AssetService;
import io.github.redisops.application.IdempotencyService;
import io.github.redisops.domain.asset.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/applications")
public class ApplicationController {
    private final AssetService assets;
    private final IdempotencyService idempotency;
    public ApplicationController(AssetService assets, IdempotencyService idempotency) {
        this.assets = assets;
        this.idempotency = idempotency;
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ManagedApplication> create(@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ApplicationRequest b, HttpServletRequest r) {
        String operator = operator(r);
        return wrap(idempotency.execute(operator, key, "APPLICATION_CREATE", b,
                () -> assets.createApplication(b.code(), b.name(), b.owner(), b.businessLine(), operator),
                a -> a.id().toString(), id -> assets.getApplication(Long.parseLong(id))), r);
    }
    @GetMapping
    public ApiResponse<List<ManagedApplication>> list(HttpServletRequest r) {
        return wrap(assets.listApplications(), r);
    }
    @GetMapping("/{id}")
    public ApiResponse<ApplicationDetail> get(@PathVariable long id, HttpServletRequest r) {
        return wrap(new ApplicationDetail(assets.getApplication(id), assets.applicationBindings(id)), r);
    }
    @PutMapping("/{id}")
    public ApiResponse<ManagedApplication> update(@PathVariable long id, @RequestHeader("If-Match") long version,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ApplicationRequest b,
            HttpServletRequest r) {
        String operator = operator(r);
        return wrap(idempotency.execute(operator, key, "APPLICATION_UPDATE", new MutationRequest(id, version, b),
                () -> assets.updateApplication(id, version, b.code(), b.name(), b.owner(), b.businessLine(), b.status(),
                        operator),
                a -> a.id().toString(), resourceId -> assets.getApplication(Long.parseLong(resourceId))), r);
    }
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, @RequestHeader("If-Match") long version,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest r) {
        String operator = operator(r);
        idempotency.executeVoid(operator, key,
                "APPLICATION_DELETE", new MutationRequest(id, version, null),
                () -> assets.deleteApplication(id, version, operator), Long.toString(id));
    }
    @PutMapping("/{id}/clusters/{clusterId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void bind(@PathVariable long id, @PathVariable long clusterId, @RequestHeader("Idempotency-Key") String key,
            @RequestBody BindingRequest b, HttpServletRequest r) {
        String operator = operator(r);
        idempotency.executeVoid(operator, key,
                "APPLICATION_BIND", new BindingMutation(id, clusterId, b), () -> assets.bind(id,
                        new ApplicationBinding(id, clusterId, b.clientType(), b.clientVersion(), b.poolConfig()),
                        operator),
                id + ":" + clusterId);
    }
    @DeleteMapping("/{id}/clusters/{clusterId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unbind(@PathVariable long id, @PathVariable long clusterId,
            @RequestHeader("Idempotency-Key") String key,
            HttpServletRequest r) {
        String operator = operator(r);
        idempotency.executeVoid(operator, key, "APPLICATION_UNBIND",
                new BindingMutation(id, clusterId, null), () -> assets.unbind(id, clusterId, operator),
                id + ":" + clusterId);
    }
    public record ApplicationRequest(@NotBlank String code, @NotBlank String name, String owner, String businessLine,
            String status) {
    }
    public record BindingRequest(String clientType, String clientVersion, String poolConfig) {
    }
    public record ApplicationDetail(ManagedApplication application, List<ApplicationBinding> bindings) {
    }
    private record MutationRequest(long id, long version, Object body) {
    }
    private record BindingMutation(long applicationId, long clusterId, Object body) {
    }
    private static String operator(HttpServletRequest r) {
        String v = r.getHeader("X-Operator");
        return v == null ? "anonymous" : v;
    }
    private static <T> ApiResponse<T> wrap(T v, HttpServletRequest r) {
        return ApiResponse.of(v, String.valueOf(r.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
