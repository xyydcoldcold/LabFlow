package com.labflow.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtEncoder;

class JwtTokenServiceTest {

    private static final String SECRET = "labflow-test-secret-is-at-least-32-bytes-long";

    @Test
    void issuedTokenCanBeValidatedAndContainsTheExpectedIdentityClaims() {
        Instant now = Instant.now();
        AuthProperties properties =
                new AuthProperties(SECRET, "https://labflow.local", Duration.ofHours(1));
        AuthConfiguration configuration = new AuthConfiguration();
        SecretKey secretKey = configuration.jwtSecretKey(properties);
        JwtEncoder encoder = configuration.jwtEncoder(secretKey);
        JwtDecoder decoder = configuration.jwtDecoder(secretKey, properties);
        AppUser user = new AppUser(
                42L,
                "scientist@example.com",
                "hash",
                "Ada Lovelace",
                now,
                now
        );
        JwtTokenService tokenService = new JwtTokenService(
                encoder,
                properties,
                Clock.fixed(now, ZoneOffset.UTC)
        );

        Jwt jwt = decoder.decode(tokenService.issueToken(user));

        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getIssuer().toString()).isEqualTo("https://labflow.local");
        assertThat(jwt.getClaimAsString("email")).isEqualTo("scientist@example.com");
        assertThat(jwt.getClaimAsString("name")).isEqualTo("Ada Lovelace");
        assertThat(jwt.getExpiresAt())
                .isEqualTo(now.truncatedTo(ChronoUnit.SECONDS).plus(Duration.ofHours(1)));
    }

    @Test
    void decoderRejectsAnExpiredToken() {
        Instant twoHoursAgo = Instant.now().minus(Duration.ofHours(2));
        AuthProperties properties =
                new AuthProperties(SECRET, "https://labflow.local", Duration.ofMinutes(30));
        AuthConfiguration configuration = new AuthConfiguration();
        SecretKey secretKey = configuration.jwtSecretKey(properties);
        JwtEncoder encoder = configuration.jwtEncoder(secretKey);
        JwtDecoder decoder = configuration.jwtDecoder(secretKey, properties);
        AppUser user = new AppUser(
                42L,
                "scientist@example.com",
                "hash",
                "Ada Lovelace",
                twoHoursAgo,
                twoHoursAgo
        );
        JwtTokenService tokenService = new JwtTokenService(
                encoder,
                properties,
                Clock.fixed(twoHoursAgo, ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> decoder.decode(tokenService.issueToken(user)))
                .isInstanceOf(JwtException.class);
    }
}
