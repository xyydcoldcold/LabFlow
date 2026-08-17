package com.labflow.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-27T12:00:00Z");
    private static final AuthProperties AUTH_PROPERTIES =
            new AuthProperties(
                    "a-secret-longer-than-thirty-two-bytes",
                    "https://labflow.local",
                    Duration.ofHours(1)
            );

    @Mock
    private AppUserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenService tokenService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository,
                passwordEncoder,
                tokenService,
                AUTH_PROPERTIES,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void registerNormalizesEmailHashesPasswordAndReturnsSession() {
        when(userRepository.existsByEmail("scientist@example.com")).thenReturn(false);
        when(passwordEncoder.encode("strong-password")).thenReturn("bcrypt-hash");
        when(userRepository.saveAndFlush(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser candidate = invocation.getArgument(0);
            return new AppUser(
                    42L,
                    candidate.getEmail(),
                    candidate.getPasswordHash(),
                    candidate.getDisplayName(),
                    candidate.getCreatedAt(),
                    candidate.getCreatedAt()
            );
        });
        when(tokenService.issueToken(any(AppUser.class))).thenReturn("signed.jwt.token");

        AuthSession session = authService.register(
                "  Scientist@Example.COM ",
                "strong-password",
                "  Ada Lovelace  "
        );

        assertThat(session.accessToken()).isEqualTo("signed.jwt.token");
        assertThat(session.expiresInSeconds()).isEqualTo(3600);
        assertThat(session.user().getId()).isEqualTo(42L);
        assertThat(session.user().getEmail()).isEqualTo("scientist@example.com");
        assertThat(session.user().getPasswordHash()).isEqualTo("bcrypt-hash");
        assertThat(session.user().getDisplayName()).isEqualTo("Ada Lovelace");
        assertThat(session.user().getCreatedAt()).isEqualTo(NOW);
        verify(passwordEncoder).encode("strong-password");
    }

    @Test
    void registerRejectsAnExistingNormalizedEmail() {
        when(userRepository.existsByEmail("scientist@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                "Scientist@Example.com",
                "strong-password",
                "Ada"
        )).isInstanceOf(EmailAlreadyRegisteredException.class);

        verify(passwordEncoder, never()).encode(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void loginVerifiesHashAndReturnsAFreshToken() {
        AppUser user = existingUser();
        when(userRepository.findByEmail("scientist@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("strong-password", "stored-bcrypt-hash")).thenReturn(true);
        when(tokenService.issueToken(user)).thenReturn("fresh.jwt.token");

        AuthSession session = authService.login(" Scientist@Example.com ", "strong-password");

        assertThat(session.user()).isSameAs(user);
        assertThat(session.accessToken()).isEqualTo("fresh.jwt.token");
        assertThat(session.expiresInSeconds()).isEqualTo(3600);
    }

    @Test
    void loginUsesTheSamePublicErrorForAnUnknownEmail() {
        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.matches(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> authService.login("unknown@example.com", "strong-password"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid email or password");

        verify(tokenService, never()).issueToken(any());
    }

    @Test
    void rejectsPasswordsLongerThanBcryptsUtf8Limit() {
        String oversizedPassword = "密".repeat(25);

        assertThatThrownBy(() -> authService.register(
                "scientist@example.com",
                oversizedPassword,
                "Ada"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("72 UTF-8 bytes");

        verify(userRepository, never()).existsByEmail(any());
    }

    private AppUser existingUser() {
        return new AppUser(
                42L,
                "scientist@example.com",
                "stored-bcrypt-hash",
                "Ada Lovelace",
                NOW,
                NOW
        );
    }
}
