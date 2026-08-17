package com.labflow.backend.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

class SecurityErrorHandlerTest {

    @Test
    void authenticationEntryPointReturnsTheStandard401Shape() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader("X-Request-ID", "request-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RestAuthenticationEntryPoint().commence(
                request,
                response,
                new BadCredentialsException("internal details must not leak")
        );

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString())
                .contains("\"status\":401")
                .contains("\"code\":\"UNAUTHORIZED\"")
                .contains("\"path\":\"/api/auth/me\"")
                .contains("\"requestId\":\"request-123\"")
                .doesNotContain("internal details");
    }

    @Test
    void accessDeniedHandlerReturnsTheStandard403Shape() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/projects/42");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RestAccessDeniedHandler().handle(
                request,
                response,
                new AccessDeniedException("internal details must not leak")
        );

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString())
                .contains("\"status\":403")
                .contains("\"code\":\"FORBIDDEN\"")
                .contains("\"path\":\"/api/projects/42\"")
                .contains("\"requestId\":null")
                .doesNotContain("internal details");
    }
}
