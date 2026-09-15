package io.github.susongyan.redisops.platform.api.distribution;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Read a bounded body before Jackson can allocate strings from an untrusted request. */
@Component
public class DistributionRequestLimitFilter extends OncePerRequestFilter {
    private static final int LIMIT = 64 * 1024;
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/key-distributions") || !"POST".equals(request.getMethod());
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
    }
    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"DISTRIBUTION_REQUEST_LIMIT\",\"message\":\"request exceeds 64 KiB\"}");
    }
}
