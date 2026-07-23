package io.github.redisops.api;

import io.github.redisops.common.BusinessException;
import io.github.redisops.domain.job.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/jobs")
public class JobController {
    private final JobRepository jobs;public JobController(JobRepository jobs){this.jobs=jobs;}
    @GetMapping("/{id}") public ApiResponse<AsyncJob> get(@PathVariable long id,HttpServletRequest request){
        AsyncJob job=jobs.findById(id).orElseThrow(()->BusinessException.notFound("job",id));
        return ApiResponse.of(job,String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
