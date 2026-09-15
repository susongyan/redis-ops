package io.github.susongyan.redisops.platform.api.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.susongyan.redisops.platform.application.identity.LocalIdentityService;
import io.github.susongyan.redisops.platform.domain.identity.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class IdentitySessionTest {
    @Test
    void currentSessionWorksButRevokedOrExpiredSessionsFailClosed() throws Exception {
        var users = mock(LocalIdentityService.class);
        when(users.get(1)).thenReturn(new PlatformUser(1, "Admin", PlatformUser.Status.ACTIVE, PlatformUser.Role.ADMIN,
                false, 0, 2, Instant.EPOCH, Instant.EPOCH, null));
        var filter = new IdentitySecurity.SessionGuard(users);
        for (int scenario = 0; scenario < 3; scenario++) {
            var req = new MockHttpServletRequest("GET", "/api/v1/clusters");
            var session = req.getSession();
            session.setAttribute(IdentitySecurity.USER_ID, 1L);
            session.setAttribute(IdentitySecurity.AUTH_VERSION, scenario == 1 ? 1L : 2L);
            session.setAttribute(IdentitySecurity.START, scenario == 2 ? 0L : System.currentTimeMillis());
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();
            filter.doFilter(req, response, chain);
            if (scenario == 0)
                assertNotNull(chain.getRequest());
            else {
                assertEquals(401, response.getStatus());
                assertNull(chain.getRequest());
            }
        }
    }
    @Test
    void forcedPasswordChangeCannotAccessBusinessEndpoints() throws Exception {
        var users = mock(LocalIdentityService.class);
        when(users.get(1)).thenReturn(new PlatformUser(1, "Admin", PlatformUser.Status.ACTIVE, PlatformUser.Role.ADMIN,
                true, 0, 0, Instant.EPOCH, Instant.EPOCH, null));
        var req = new MockHttpServletRequest("GET", "/api/v1/clusters");
        req.getSession().setAttribute(IdentitySecurity.USER_ID, 1L);
        req.getSession().setAttribute(IdentitySecurity.AUTH_VERSION, 0L);
        req.getSession().setAttribute(IdentitySecurity.START, System.currentTimeMillis());
        var response = new MockHttpServletResponse();
        new IdentitySecurity.SessionGuard(users).doFilter(req, response, new MockFilterChain());
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("PASSWORD_CHANGE_REQUIRED"));
    }
    @Test
    void passwordsAreHashedAndNeverIncludedInCredentialToString() {
        var port = new IdentitySecurity().passwordHashPort();
        char[] input = "temporary-password-123".toCharArray();
        String hash = port.hash(input);
        assertTrue(port.matches(input, hash));
        assertFalse(port.matches("wrong-password".toCharArray(), hash));
        assertFalse(new LocalCredential(1, hash).toString().contains(hash));
    }
}
