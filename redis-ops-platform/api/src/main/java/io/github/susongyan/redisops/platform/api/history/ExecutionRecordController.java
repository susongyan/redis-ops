package io.github.susongyan.redisops.platform.api.history;

import io.github.susongyan.redisops.platform.application.IdempotencyService;
import io.github.susongyan.redisops.platform.application.history.ExecutionRecordService;
import io.github.susongyan.redisops.platform.domain.history.ExecutionRecordRepository.Type;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/execution-records")
public class ExecutionRecordController {
    private final ExecutionRecordService service;
    private final IdempotencyService idempotency;
    public ExecutionRecordController(ExecutionRecordService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }
    @DeleteMapping("/{type}/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Type type, @PathVariable long id, @RequestHeader("If-Match") long version,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        String operator = request.getUserPrincipal().getName();
        idempotency.executeVoid(operator, key, "EXECUTION_RECORD_DELETE",
                Map.of("type", type, "id", id, "version", version),
                () -> service.delete(type, id, version, operator), type + ":" + id);
    }
}
