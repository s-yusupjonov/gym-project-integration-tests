package com.gym.integration.cucumber;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Mints the internal service-to-service JWT that trainer-workload-service's
 * {@code JwtAuthenticationFilter}/{@code JwtTokenValidator} require on every {@code /api/**} call,
 * with subject "gym-crm" so it passes the {@code internal-auth.allowed-callers} check.
 *
 * <p>gym-crm itself never needs to mint one of these - it only talks to trainer-workload-service
 * over JMS, never over this REST API (confirmed: no RestTemplate/WebClient/Feign client anywhere
 * in gym-crm's source). This harness plays the part of "a caller with a valid internal token",
 * standing in for whatever future consumer of trainer-workload-service's read API would present one.
 *
 * <p>The secret here is passed to trainer-workload-service's {@code internal-auth.secret} property
 * at startup (see {@link IntegrationTestEnvironment#workloadArgs}), so tokens signed here are
 * always valid for the instance under test - it is not trainer-workload-service's real production
 * secret, which is externally configured per-environment.
 */
final class InternalJwtSupport {

    static final String SECRET = "it-shared-internal-secret-do-not-use-in-prod";
    static final String ALLOWED_CALLER = "gym-crm";

    private static final SecretKey SECRET_KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private InternalJwtSupport() {
    }

    static String validGymCrmToken() {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(ALLOWED_CALLER)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(10))))
                .signWith(SECRET_KEY)
                .compact();
    }
}
