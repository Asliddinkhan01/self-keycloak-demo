package uz.platform.cadastralservice.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import uz.platform.security.MembershipVerifier;

/**
 * Answers the membership question by asking organization-service.
 *
 * <p>cadastral-service cannot read {@code user_organizations}: its PostgreSQL
 * role has no privileges on that schema, and a query would be refused by the
 * database. That is the intended shape of a microservice system — the service
 * that owns the data answers questions about it — so this class makes an HTTP
 * call with its own service token instead.</p>
 *
 * <h2>Why it is cached, and why only briefly</h2>
 *
 * <p>Without a cache, every organization-scoped request would cost a network
 * round trip, and a page that lists projects would multiply that. With a cache
 * that never expires, revoking a membership would not take effect until restart.
 * Sixty seconds is the compromise: a burst of requests from one person costs one
 * call, and a revocation takes effect within a minute.</p>
 *
 * <p>The cache is deliberately tiny and hand-written rather than a library. What
 * it must do is small, and the behaviour on failure matters more than the
 * eviction policy: <b>a failed lookup is not cached</b>, so a transient outage at
 * organization-service does not freeze a wrong answer in place for a minute.</p>
 */
@Component
public class RemoteMembershipVerifier implements MembershipVerifier {

    private static final Logger log = LoggerFactory.getLogger(RemoteMembershipVerifier.class);

    private static final ParameterizedTypeReference<Map<String, Object>> RESPONSE =
            new ParameterizedTypeReference<>() {
            };

    private final WebClient organizationService;
    private final Duration ttl;
    private final Map<UUID, CachedMemberships> cache = new ConcurrentHashMap<>();

    public RemoteMembershipVerifier(WebClient organizationServiceClient,
                                    @Value("${app.organization-service.membership-cache-ttl-seconds}") long ttlSeconds) {
        this.organizationService = organizationServiceClient;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    @Override
    public boolean isMember(UUID keycloakSub, String tin) {
        CachedMemberships cached = cache.get(keycloakSub);
        if (cached != null && cached.isFresh(ttl)) {
            return cached.tins().contains(tin);
        }

        Set<String> tins = fetch(keycloakSub);
        if (tins == null) {
            // Fail closed. Refusing a legitimate request during an outage is
            // recoverable; allowing an illegitimate one is not.
            return false;
        }

        cache.put(keycloakSub, new CachedMemberships(tins, Instant.now()));
        return tins.contains(tin);
    }

    /** @return the active TINs, or null when the call failed. */
    @SuppressWarnings("unchecked")
    private Set<String> fetch(UUID keycloakSub) {
        try {
            Map<String, Object> body = organizationService.get()
                    .uri("/internal/memberships/{sub}", keycloakSub)
                    .retrieve()
                    .bodyToMono(RESPONSE)
                    .block(Duration.ofSeconds(5));

            if (body == null || !(body.get("activeTins") instanceof List<?> tins)) {
                log.warn("Unexpected membership response shape for subject {}", keycloakSub);
                return null;
            }
            return Set.copyOf(((List<String>) tins));
        } catch (RuntimeException ex) {
            // The message, not the stack: this is an expected failure mode, and
            // a full trace on every request during an outage buries the signal.
            log.error("Membership lookup failed for subject {}: {}", keycloakSub, ex.getMessage());
            return null;
        }
    }

    private record CachedMemberships(Set<String> tins, Instant loadedAt) {

        boolean isFresh(Duration ttl) {
            return Instant.now().isBefore(loadedAt.plus(ttl));
        }
    }
}
