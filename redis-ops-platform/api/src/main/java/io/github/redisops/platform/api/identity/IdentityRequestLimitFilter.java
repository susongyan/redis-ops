package io.github.redisops.platform.api.identity;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Read a bounded body before Jackson can allocate strings from an untrusted request. */
@Component
public class IdentityRequestLimitFilter extends OncePerRequestFilter {
    private static final int LIMIT = 16 * 1024;
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(request.getRequestURI().startsWith("/api/v1/auth/")
                || request.getRequestURI().startsWith("/api/v1/users"))
                || !java.util.Set.of("POST", "PATCH").contains(request.getMethod());
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        response.setHeader("Cache-Control", "no-store");
        if (request.getContentLengthLong() > LIMIT) {
            reject(response);
            return;
        }
        byte[] bytes = request.getInputStream().readNBytes(LIMIT + 1);
        if (bytes.length > LIMIT) {
            reject(response);
            return;
        }
        try {
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override
                public ServletInputStream getInputStream() {
                    ByteArrayInputStream input = new ByteArrayInputStream(bytes);
                    return new ServletInputStream() {
                        public int read() {
                            return input.read();
                        }
                        public int read(byte[] target, int offset, int length) {
                            return input.read(target, offset, length);
                        }
                        public boolean isFinished() {
                            return input.available() == 0;
                        }
                        public boolean isReady() {
                            return true;
                        }
                        public void setReadListener(ReadListener listener) {
                            throw new UnsupportedOperationException("SYNCHRONOUS_REQUEST_ONLY");
                        }
                    };
                }
            }, response);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }
    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"IDENTITY_REQUEST_LIMIT\",\"message\":\"request exceeds 16 KiB\"}");
    }
}
