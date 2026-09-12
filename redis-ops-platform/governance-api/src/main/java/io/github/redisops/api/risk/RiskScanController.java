package io.github.redisops.api.risk;
import io.github.redisops.api.*;
import io.github.redisops.application.IdempotencyService;
import io.github.redisops.application.risk.RiskScanService;
import io.github.redisops.common.PageResult;
import io.github.redisops.domain.risk.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
@RestController
public class RiskScanController {
    private final RiskScanService s;
    private final IdempotencyService i;
    public RiskScanController(RiskScanService s, IdempotencyService i) {
        this.s = s;
        this.i = i;
    }
    @PostMapping("/api/v1/risk-scan-tasks")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<RiskScanTask> create(@RequestHeader("Idempotency-Key") String k, @RequestBody Create b,
            HttpServletRequest r) {
        String o = op(r);
        return wrap(i.execute(
                o, k, "RISK_SCAN_CREATE", b, () -> s.create(b.clusterId, b.databaseNo, b.includePattern,
                        b.largeKeyThresholdBytes, b.scanRatePerSecond, b.maxFindings),
                x -> x.id().toString(), x -> s.get(Long.parseLong(x))), r);
    }
    @GetMapping("/api/v1/risk-scan-tasks")
    ApiResponse<List<RiskScanTask>> list(HttpServletRequest r) {
        return wrap(s.list(), r);
    }
    @GetMapping("/api/v1/risk-scan-tasks/{id}")
    ApiResponse<Detail> get(@PathVariable long id, HttpServletRequest r) {
        return wrap(new Detail(s.get(id), s.latest(id).orElse(null), s.checkpoints(id), s.summary(id)), r);
    }
    @PostMapping("/api/v1/risk-scan-tasks/{id}/start")
    ApiResponse<RiskScanTask> start(@PathVariable long id, @RequestHeader("Idempotency-Key") String k,
            @RequestHeader("If-Match") long v, HttpServletRequest r) {
        return wrap(i.execute(op(r), k, "RISK_SCAN_START", Map.of("id", id), () -> s.start(id, v, k),
                x -> x.id().toString(), x -> s.get(Long.parseLong(x))), r);
    }
    @PostMapping("/api/v1/risk-scan-tasks/{id}/cancel")
    ApiResponse<RiskScanTask> cancel(@PathVariable long id, @RequestHeader("Idempotency-Key") String k,
            @RequestHeader("If-Match") long v, HttpServletRequest r) {
        return wrap(i.execute(op(r), k, "RISK_SCAN_CANCEL", Map.of("id", id), () -> s.cancel(id, v),
                x -> x.id().toString(), x -> s.get(Long.parseLong(x))), r);
    }
    @GetMapping("/api/v1/risk-scan-tasks/{id}/findings")
    ApiResponse<PageResult<RiskFinding>> findings(@PathVariable long id, @RequestParam(defaultValue = "1") int p,
            @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "") String riskType,
            HttpServletRequest r) {
        return wrap(s.findings(id, p, size, riskType), r);
    }
    record Create(@Positive long clusterId, @Min(0) Integer databaseNo, String includePattern,
            @Min(1) Long largeKeyThresholdBytes, @Min(1) @Max(100000) Integer scanRatePerSecond,
            @Min(1) @Max(100000) Integer maxFindings) {
    }
    record Detail(RiskScanTask task, RiskScanRun latestRun, List<RiskScanCheckpoint> checkpoints,
            RiskScanService.RiskSummary summary) {
    }
    private static String op(HttpServletRequest r) {
        String x = r.getHeader("X-Operator");
        return x == null ? "anonymous" : x;
    }
    private static <T> ApiResponse<T> wrap(T x, HttpServletRequest r) {
        return ApiResponse.of(x, String.valueOf(r.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
