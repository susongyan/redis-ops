package io.github.redisops.api;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.UUID;

@Component
public class RequestIdFilter implements Filter {
    public static final String ATTRIBUTE = "requestId";
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        String id = http.getHeader("X-Request-Id");
        if (id == null || id.isBlank())
            id = UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, id);
        ((HttpServletResponse) response).setHeader("X-Request-Id", id);
        chain.doFilter(request, response);
    }
}
