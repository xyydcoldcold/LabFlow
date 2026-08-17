package com.labflow.backend.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final String DUMMY_BCRYPT_HASH =
            "$2a$12$9QzjRe7bQX4lL7l54BmQPOb8m8mT4p79InU3iYRTQOGYr.lA0Lx7e";

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokenService;
    private final AuthProperties authProperties;
    private final Clock clock;

    public AuthService(
            AppUserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenService tokenService,
            AuthProperties authProperties,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.authProperties = authProperties;
        this.clock = clock;
    }

    @Transactional
    public AuthSession register(String email, String rawPassword, String displayName) {
        String normalizedEmail = normalizeEmail(email);
        validatePassword(rawPassword);
        String normalizedDisplayName = displayName.trim();

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new EmailAlreadyRegisteredException();
        }

        AppUser user = new AppUser(
                normalizedEmail,
                passwordEncoder.encode(rawPassword),
                normalizedDisplayName,
                clock.instant()
        );
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw new EmailAlreadyRegisteredException();
        }

        return createSession(user);
    }

    @Transactional(readOnly = true)
    public AuthSession login(String email, String rawPassword) {
        String normalizedEmail = normalizeEmail(email);
        validatePassword(rawPassword);
        AppUser user = userRepository.findByEmail(normalizedEmail).orElse(null);
        String passwordHash = user == null ? DUMMY_BCRYPT_HASH : user.getPasswordHash();

        if (!passwordEncoder.matches(rawPassword, passwordHash) || user == null) {
            throw new InvalidCredentialsException();
        }

        return createSession(user);
    }

    @Transactional(readOnly = true)
    public AppUser getCurrentUser(long userId) {
        return userRepository.findById(userId).orElseThrow(InvalidCredentialsException::new);
    }

    private AuthSession createSession(AppUser user) {
        return new AuthSession(user, tokenService.issueToken(user), authProperties.ttl().toSeconds());
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private void validatePassword(String rawPassword) {
        int utf8Length = rawPassword.getBytes(StandardCharsets.UTF_8).length;
        if (rawPassword.length() < 8 || utf8Length > 72) {
            throw new IllegalArgumentException("Password must be at least 8 characters and at most 72 UTF-8 bytes");
        }
    }
}
