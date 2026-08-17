package com.labflow.backend.common.api;

import java.io.IOException;
import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;

public final class SecurityErrorWriter {

    private SecurityErrorWriter() {
    }

    public static void write(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String message
    ) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String requestId = request.getHeader("X-Request-ID");
        response.getWriter().write("""
                {"timestamp":"%s","status":%d,"code":"%s","message":"%s","path":"%s","requestId":%s}
                """.formatted(
                Instant.now(),
                status,
                escape(code),
                escape(message),
                escape(request.getRequestURI()),
                requestId == null ? "null" : "\"" + escape(requestId) + "\""
        ).strip());
    }

    private static String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
