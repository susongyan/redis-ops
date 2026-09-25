package io.github.susongyan.redisops.platform.api.operation;

import io.github.susongyan.redisops.platform.application.IdempotencyService;
import io.github.susongyan.redisops.platform.application.operation.RedisOperationService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.mockito.Mockito.*;

class OperationIdentityTest {
    @Test
    void actorHeaderCannotOverrideAuthenticatedRequesterApproverOrExecutor() {
        var service = mock(RedisOperationService.class);
        var controller = new RedisOperationController(service, mock(IdempotencyService.class));
        var request = new MockHttpServletRequest();
        request.setUserPrincipal(() -> "user:7");
        request.addHeader("X-Operator", "admin");
        var body = new RedisOperationController.Request(1, 0, "GET", List.of("fixture"));
        controller.create("key", body, request);
        controller.approve(1, 0, new RedisOperationController.Note("approved"), request);
        controller.execute(1, 1, body, request);
        verify(service).request(1, 0, "GET", List.of("fixture"), "user:7", "key");
        verify(service).approve(1, 0, "user:7", "approved");
        verify(service).execute(1, 1, "user:7", List.of("fixture"));
    }
}
