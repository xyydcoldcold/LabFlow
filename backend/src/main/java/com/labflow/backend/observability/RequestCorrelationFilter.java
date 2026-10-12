package com.labflow.backend.observability;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestCorrelationFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestCorrelationFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        String supplied = request.getHeader("X-Request-ID");
        String requestId = supplied != null && supplied.matches("[A-Za-z0-9._-]{1,64}")
                ? supplied : UUID.randomUUID().toString();
        request.setAttribute("requestId", requestId);
        response.setHeader("X-Request-ID", requestId);
        MDC.put("requestId", requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Bodies, query strings, authorization headers and attempt tokens are never logged.
            log.atInfo().addKeyValue("method", request.getMethod()).addKeyValue("status", response.getStatus())
                    .log(request.isAsyncStarted() ? "HTTP async request opened" : "HTTP request completed");
            if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
        }
    }
}
