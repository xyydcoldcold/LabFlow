package com.labflow.backend.worker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class WorkerServiceTokenFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";
    private final byte[] expectedToken;

    WorkerServiceTokenFilter(WorkerProperties properties) {
        this.expectedToken = properties.serviceToken().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith(PREFIX)) {
            byte[] supplied = authorization.substring(PREFIX.length()).getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(expectedToken, supplied)) {
                var authentication = UsernamePasswordAuthenticationToken.authenticated(
                        "labflow-worker",
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_WORKER"))
                );
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        filterChain.doFilter(request, response);
    }
}
