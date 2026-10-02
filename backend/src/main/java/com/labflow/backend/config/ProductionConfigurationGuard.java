package com.labflow.backend.config;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class ProductionConfigurationGuard {

    public ProductionConfigurationGuard(Environment environment) {
        requireValue(environment, "DB_URL");
        requireValue(environment, "DB_USERNAME");
        requireSecret(environment, "DB_PASSWORD", 16);
        requireValue(environment, "RABBITMQ_HOST");
        requireValue(environment, "RABBITMQ_USERNAME");
        requireSecret(environment, "RABBITMQ_PASSWORD", 16);
        requireSecret(environment, "AUTH_JWT_SECRET", 32);
        requireSecret(environment, "WORKER_SERVICE_TOKEN", 32);
        String issuer = requireValue(environment, "AUTH_JWT_ISSUER");
        if (!issuer.startsWith("https://") || issuer.contains("labflow.local")) {
            throw new IllegalStateException("AUTH_JWT_ISSUER must be the production HTTPS issuer");
        }
        requireValue(environment, "ARTIFACT_ROOT");
    }

    private static String requireValue(Environment environment, String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank() || value.contains("${")) {
            throw new IllegalStateException(name + " must be explicitly configured in production");
        }
        return value;
    }

    private static void requireSecret(Environment environment, String name, int minimumBytes) {
        String value = requireValue(environment, name);
        String normalized = value.toLowerCase(Locale.ROOT);
        if (value.getBytes(StandardCharsets.UTF_8).length < minimumBytes
                || normalized.contains("change-me")
                || normalized.contains("replace-with")
                || normalized.contains("dev_password")
                || normalized.contains("local-development")
                || normalized.contains("local-worker")) {
            throw new IllegalStateException(name + " must be a non-development secret of at least "
                    + minimumBytes + " UTF-8 bytes");
        }
    }
}
