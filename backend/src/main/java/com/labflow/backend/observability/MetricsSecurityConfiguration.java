package com.labflow.backend.observability;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.labflow.backend.common.api.RestAccessDeniedHandler;
import com.labflow.backend.common.api.RestAuthenticationEntryPoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class MetricsSecurityConfiguration {
    @Bean
    @Order(0)
    SecurityFilterChain metricsSecurityFilterChain(HttpSecurity http,
            @Value("${labflow.metrics.scrape-token}") String token,
            RestAuthenticationEntryPoint unauthorized, RestAccessDeniedHandler forbidden) throws Exception {
        if (token.isBlank()) throw new IllegalArgumentException("Metrics scrape token must not be blank");
        byte[] expected = token.getBytes(StandardCharsets.UTF_8);
        var authentication = new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {
                String header = request.getHeader("Authorization");
                if (header != null && header.startsWith("Bearer ") && MessageDigest.isEqual(expected,
                        header.substring(7).getBytes(StandardCharsets.UTF_8))) {
                    SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                            "metrics-scraper", null, List.of(new SimpleGrantedAuthority("ROLE_METRICS"))));
                }
                chain.doFilter(request, response);
            }
        };
        return http.securityMatcher("/actuator/prometheus").csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("METRICS"))
                .addFilterBefore(authentication, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
                .build();
    }
}
