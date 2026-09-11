package uz.platform.security;

import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServletOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Builds the outbound side of a service: how it calls another one, as itself.
 *
 * <h2>Machine identity, not a borrowed human one</h2>
 *
 * <p>Nothing built here forwards the caller's token. When a service needs data
 * from another service it asks <b>as itself</b>, with a token obtained through
 * the client-credentials grant. That token has no human subject, carries
 * {@code token_use: service}, and grants only the client roles its service
 * account was given.</p>
 *
 * <p>The shortcut people reach for — giving a service a {@code SUPER_ADMIN}
 * account — fails in several ways at once. It abandons least privilege, since a
 * service that only needs to read memberships could then delete users. It
 * destroys audit meaning, because every machine action is recorded as an
 * administrator. It makes one compromised service equal to full platform
 * authority. And it cannot be revoked without also revoking the humans who share
 * the role. A machine identity and a business role are different things.</p>
 *
 * <h2>Forwarding the user's token is a different pattern</h2>
 *
 * <p>The alternative is to pass the caller's own token downstream, so the callee
 * applies that person's permissions. It is legitimate, and it is not what this
 * platform does. Acting as itself means the callee can apply <em>machine</em>
 * permissions — organization-service grants {@code ORG_READ} to one service and
 * {@code ORG_MEMBERSHIP_SYNC} to another — which is exactly the scoping that a
 * forwarded user token cannot express.</p>
 *
 * <h2>No hand-written token cache</h2>
 *
 * <p>{@link AuthorizedClientServiceOAuth2AuthorizedClientManager} is the manager
 * meant for machine-to-machine calls: it stores the authorized client outside any
 * user session and re-obtains the token as it nears expiry. Hand-rolled caching
 * is where refresh bugs live, so it is left to Spring.</p>
 */
public final class ServiceWebClients {

    private ServiceWebClients() {
    }

    /**
     * A manager that obtains and renews client-credentials tokens.
     *
     * <p>The service-backed manager is used rather than the request-backed one
     * because there is no user session behind a client-credentials token, and
     * often no HTTP request to tie it to either — a scheduled job has neither.</p>
     */
    public static OAuth2AuthorizedClientManager clientCredentialsManager(
            ClientRegistrationRepository clientRegistrations,
            OAuth2AuthorizedClientService authorizedClients) {

        OAuth2AuthorizedClientProvider provider = OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials()
                .build();

        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(clientRegistrations, authorizedClients);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }

    /**
     * A WebClient that attaches the service token to every outgoing request.
     *
     * <p>Call sites end up with no security code at all: they just make an HTTP
     * call, and the filter function asks the manager for a token and sets the
     * Authorization header.</p>
     *
     * @param registrationId must match a {@code spring.security.oauth2.client.registration.*} key
     */
    public static WebClient forRegistration(OAuth2AuthorizedClientManager authorizedClientManager,
                                            String registrationId,
                                            String baseUrl) {

        ServletOAuth2AuthorizedClientExchangeFilterFunction oauth2 =
                new ServletOAuth2AuthorizedClientExchangeFilterFunction(authorizedClientManager);
        // Which registration to use when a call does not name one. Without it the
        // filter looks for a token belonging to the current user, and there is no
        // user behind a service-to-service call.
        oauth2.setDefaultClientRegistrationId(registrationId);

        return WebClient.builder()
                .baseUrl(baseUrl)
                .apply(oauth2.oauth2Configuration())
                .build();
    }
}
