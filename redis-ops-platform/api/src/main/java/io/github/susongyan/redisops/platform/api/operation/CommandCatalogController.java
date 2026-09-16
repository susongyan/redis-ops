package io.github.susongyan.redisops.platform.api.operation;

import io.github.susongyan.redisops.platform.api.ApiResponse;
import io.github.susongyan.redisops.platform.application.IdempotencyService;
import io.github.susongyan.redisops.platform.application.operation.CommandCatalogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/operation-commands")
public class CommandCatalogController {
    private final CommandCatalogService catalog;
    private final IdempotencyService idempotency;
    public CommandCatalogController(CommandCatalogService catalog, IdempotencyService idempotency) {
        this.catalog = catalog;
        this.idempotency = idempotency;
    }
    @PostMapping
    ApiResponse<?> create(@RequestHeader("Idempotency-Key") String key,
            @RequestBody CommandCatalogService.Definition body, HttpServletRequest request) {
        String actor = request.getUserPrincipal().getName();
        return ApiResponse.of(
                idempotency.execute(actor, key, "COMMAND_DEFINE", body, () -> catalog.define(null, 0, body, actor),
                        x -> x.id().toString(), id -> catalog.get(Long.parseLong(id))),
                String.valueOf(request.getAttribute("requestId")));
    }
    @PutMapping("/{id}/definition")
    ApiResponse<?> update(@PathVariable long id, @RequestHeader("If-Match") long version,
            @RequestHeader("Idempotency-Key") String key, @RequestBody CommandCatalogService.Definition body,
            HttpServletRequest request) {
        String actor = request.getUserPrincipal().getName();
        return ApiResponse.of(
                idempotency.execute(actor, key, "COMMAND_DEFINE_UPDATE:" + id, java.util.List.of(version, body),
                        () -> catalog.define(id, version, body, actor), x -> x.id().toString(),
                        saved -> catalog.get(Long.parseLong(saved))),
                String.valueOf(request.getAttribute("requestId")));
    }
}
