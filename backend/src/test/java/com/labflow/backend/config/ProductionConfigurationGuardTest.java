package com.labflow.backend.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionConfigurationGuardTest {

    @Test
    void acceptsExplicitProductionSettings() {
        assertThatCode(() -> new ProductionConfigurationGuard(validEnvironment())).doesNotThrowAnyException();
    }

    @Test
    void refusesDevelopmentCredentialsWithoutIncludingTheirValuesInTheError() {
        String secret = "labflow-local-development-jwt-secret-change-me";
        assertThatThrownBy(() -> new ProductionConfigurationGuard(
                validEnvironment().withProperty("AUTH_JWT_SECRET", secret)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_JWT_SECRET")
                .hasMessageNotContaining(secret);
    }

    @Test
    void requiresExplicitCredentialsAndHttpsIssuer() {
        MockEnvironment environment = validEnvironment();
        environment.getPropertySources().remove("mockProperties");
        assertThatThrownBy(() -> new ProductionConfigurationGuard(environment))
                .hasMessageContaining("DB_URL must be explicitly configured");
        assertThatThrownBy(() -> new ProductionConfigurationGuard(
                validEnvironment().withProperty("AUTH_JWT_ISSUER", "http://labflow.example.org")))
                .hasMessageContaining("production HTTPS issuer");
    }

    private MockEnvironment validEnvironment() {
        return new MockEnvironment()
                .withProperty("DB_URL", "jdbc:postgresql://postgres:5432/labflow")
                .withProperty("DB_USERNAME", "labflow")
                .withProperty("DB_PASSWORD", "a".repeat(64))
                .withProperty("RABBITMQ_HOST", "rabbitmq")
                .withProperty("RABBITMQ_USERNAME", "labflow")
                .withProperty("RABBITMQ_PASSWORD", "b".repeat(64))
                .withProperty("AUTH_JWT_SECRET", "c".repeat(64))
                .withProperty("WORKER_SERVICE_TOKEN", "d".repeat(64))
                .withProperty("AUTH_JWT_ISSUER", "https://labflow.example.org")
                .withProperty("ARTIFACT_ROOT", "/var/lib/labflow/artifacts");
    }
}
