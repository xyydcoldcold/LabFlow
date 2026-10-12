package com.labflow.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestCorrelationFilterTest {
    @AfterEach void clear() { MDC.clear(); }

    @Test
    void propagatesSafeRequestIdAndRestoresThreadContextAfterFailure() {
        MDC.put("existing", "parent");
        var request = new MockHttpServletRequest("POST", "/api/jobs");
        var response = new MockHttpServletResponse();
        request.addHeader("X-Request-ID", "client-123");
        try {
            new RequestCorrelationFilter().doFilter(request, response, (req, res) -> {
                assertThat(MDC.get("requestId")).isEqualTo("client-123");
                throw new IllegalStateException("test failure");
            });
        } catch (Exception expected) {
            assertThat(expected).isInstanceOf(IllegalStateException.class);
        }
        assertThat(response.getHeader("X-Request-ID")).isEqualTo("client-123");
        assertThat(request.getAttribute("requestId")).isEqualTo("client-123");
        assertThat(MDC.getCopyOfContextMap()).containsOnlyKeys("existing");
    }

    @Test
    void replacesUnboundedOrUnsafeInputAndDoesNotLeakContextIntoNextRequest() throws Exception {
        for (String value : new String[] {"x".repeat(65), "unsafe\r\nfield: injected", "", "user@example.com"}) {
            var request = new MockHttpServletRequest("GET", "/api/auth/me");
            var response = new MockHttpServletResponse();
            request.addHeader("X-Request-ID", value);
            new RequestCorrelationFilter().doFilter(request, response, (req, res) -> {
                assertThat(MDC.get("requestId")).matches("[0-9a-f-]{36}");
            });
            assertThat(response.getHeader("X-Request-ID")).matches("[0-9a-f-]{36}");
            assertThat(MDC.get("requestId")).isNull();
        }
    }
}
