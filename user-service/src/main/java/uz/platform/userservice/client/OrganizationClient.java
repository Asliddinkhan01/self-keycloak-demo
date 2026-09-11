package uz.platform.userservice.client;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Talks to organization-service, as a service.
 *
 * <p>Every call here goes out with a client-credentials token that Spring
 * obtained, cached and will renew. Nothing in this class mentions a token: the
 * WebClient built in {@code ServiceClientConfig} attaches it.</p>
 */
@Component
public class OrganizationClient {

    private static final Logger log = LoggerFactory.getLogger(OrganizationClient.class);

    private static final ParameterizedTypeReference<Map<String, Object>> RESPONSE =
            new ParameterizedTypeReference<>() {
            };

    private final WebClient organizationService;

    public OrganizationClient(WebClient organizationServiceClient) {
        this.organizationService = organizationServiceClient;
    }

    /**
     * Reconciles one person's memberships from what OneID reported.
     *
     * <p>Needs the {@code ORG_MEMBERSHIP_SYNC} client role, which this service
     * has and cadastral-service does not.</p>
     *
     * <p>A failure is logged and swallowed, returning an empty list. Session
     * bootstrap is not worth failing a login over: the person can still use every
     * endpoint that does not need organization context, and the next call retries.
     * This is a deliberate availability choice, and it is safe precisely because
     * reconciliation only ever adds what OneID already asserted — it never grants
     * access that the membership table would otherwise refuse.</p>
     */
    public List<String> syncMemberships(UUID keycloakSub, List<String> tinsFromToken) {
        try {
            Map<String, Object> body = organizationService.post()
                    .uri("/internal/memberships/{sub}/sync", keycloakSub)
                    .bodyValue(Map.of("tins", tinsFromToken))
                    .retrieve()
                    .bodyToMono(RESPONSE)
                    .block(Duration.ofSeconds(5));

            if (body != null && body.get("activeTins") instanceof List<?> tins) {
                return tins.stream().map(String::valueOf).toList();
            }
            log.warn("Unexpected membership sync response for subject {}", keycloakSub);
        } catch (RuntimeException ex) {
            // The message, not the stack: an outage downstream should not bury
            // the log in traces on every login.
            log.error("Membership sync failed for subject {}: {}", keycloakSub, ex.getMessage());
        }
        return List.of();
    }
}
