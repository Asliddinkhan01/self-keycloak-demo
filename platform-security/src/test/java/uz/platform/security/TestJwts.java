package uz.platform.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Decoded tokens shaped like the ones Keycloak issues in this realm.
 *
 * <p>These tests start after signature, issuer and expiry checks: they exercise
 * what the platform does with a token it has already accepted. Whether a bad
 * token is refused is proven against the real Keycloak in the e2e-tests module.</p>
 */
final class TestJwts {

    static final UUID ALI = UUID.fromString("00000000-0000-0000-0000-00000000a11a");

    private TestJwts() {
    }

    /** A person signed in through platform-web. */
    static Jwt user(UUID subject, String... realmRoles) {
        return base(subject)
                .claim("preferred_username", "test-user")
                .claim("azp", "platform-web")
                .claim("token_use", "user")
                .claim("realm_access", Map.of("roles", List.of(realmRoles)))
                .build();
    }

    /** A service using client_credentials, holding client roles on organization-service. */
    static Jwt service(String clientId, String... organizationServiceRoles) {
        return base(UUID.randomUUID())
                .claim("azp", clientId)
                .claim("token_use", "service")
                .claim("resource_access",
                        Map.of("organization-service", Map.of("roles", List.of(organizationServiceRoles))))
                .build();
    }

    static Jwt.Builder base(UUID subject) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject(subject.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300));
    }
}
