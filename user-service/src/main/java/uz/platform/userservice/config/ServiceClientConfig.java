package uz.platform.userservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.reactive.function.client.WebClient;

import uz.platform.security.ServiceWebClients;

/**
 * How user-service calls organization-service.
 *
 * <p>Identical wiring to cadastral-service, and deliberately so — the mechanism
 * is shared in {@code ServiceWebClients}, and only the registration differs. What
 * is <b>not</b> identical is what the two service accounts may do:</p>
 *
 * <pre>
 * user-service       ORG_MEMBERSHIP_SYNC, ORG_READ   reads and writes memberships
 * cadastral-service  ORG_READ                        reads only
 * </pre>
 *
 * <p>Both are narrower than "a trusted internal service", which is the useful
 * part. Least privilege between services is not a slogan; it is two rows of
 * different client roles in the realm.</p>
 */
@Configuration
public class ServiceClientConfig {

    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrations,
            OAuth2AuthorizedClientService authorizedClients) {
        return ServiceWebClients.clientCredentialsManager(clientRegistrations, authorizedClients);
    }

    @Bean
    public WebClient organizationServiceClient(
            OAuth2AuthorizedClientManager authorizedClientManager,
            @Value("${app.organization-service.base-url}") String baseUrl) {
        return ServiceWebClients.forRegistration(authorizedClientManager, "organization-service", baseUrl);
    }
}
