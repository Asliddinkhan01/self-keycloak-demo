package uz.platform.cadastralservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.reactive.function.client.WebClient;

import uz.platform.security.ServiceWebClients;

/**
 * How cadastral-service calls organization-service.
 *
 * <p>This service is both an OAuth2 <b>resource server</b>, receiving tokens, and
 * an OAuth2 <b>client</b>, obtaining its own. The two roles are opposites and it
 * is worth keeping them straight: the resource server side is configured under
 * {@code spring.security.oauth2.resourceserver}, the client side under
 * {@code spring.security.oauth2.client}.</p>
 *
 * <p>Its service account holds exactly one client role on organization-service,
 * {@code ORG_READ}. It cannot create, update or delete an organization, cannot
 * reconcile memberships, and holds no {@code USER_*} permission anywhere. That
 * is least privilege as a configuration fact rather than an intention.</p>
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
