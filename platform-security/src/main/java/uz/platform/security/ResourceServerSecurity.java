package uz.platform.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The security baseline every microservice in this platform shares.
 *
 * <p>A service opts in with {@code @Import(ResourceServerSecurity.class)}. That is
 * deliberately explicit rather than a Spring Boot auto-configuration: in a project
 * whose purpose is to be understood, a reader should be able to see <em>why</em>
 * a service is secured by following one annotation, instead of discovering that
 * something on the classpath configured it invisibly.</p>
 *
 * <h2>What this sets up</h2>
 * <ul>
 *   <li>The service is an OAuth2 <b>resource server</b>. It never logs anyone in,
 *       never holds a session, and never sees a password. Its only job is to
 *       decide whether an already-issued token may do what it is asking.</li>
 *   <li>Every request needs a valid token except the small public set below.</li>
 *   <li>JWT validation is turned on. The {@code issuer-uri} in each service's
 *       application.yml is what tells Spring where to fetch Keycloak's public
 *       keys from.</li>
 * </ul>
 *
 * <h2>Why each service validates independently</h2>
 *
 * <p>The API Gateway also validates the token, and that is not redundant. If the
 * gateway were the only check, anything that could reach a service directly would
 * bypass security entirely: a misrouted internal call, a port-forward during
 * debugging, a compromised neighbour, or a future infrastructure change that
 * quietly exposes a port. A service must assume the network is hostile and prove
 * for itself who is calling.</p>
 *
 * <p>Equally important, and the reason the gateway forwards the token unchanged:
 * a service must never trust a header such as {@code X-User-Id}. That would turn
 * a signed, verifiable assertion into a forgeable string.</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity // switches on @PreAuthorize, used from phase 5 onward
public class ResourceServerSecurity {

    /**
     * Paths reachable without any token at all.
     *
     * <p>Kept to an explicit, tiny list. Anything not named here requires a valid
     * token, because {@code anyRequest().authenticated()} is the last rule: a new
     * endpoint is protected by default rather than accidentally public.</p>
     */
    public static final String[] PUBLIC_PATHS = {
            "/api/public/**",
            "/actuator/health",
            "/actuator/health/**"
    };

    /**
     * Endpoints meant for other services, never for a browser.
     *
     * <p>Enforced as a URL rule rather than only an annotation, so that a new
     * internal endpoint is protected the moment it is added to this path, with
     * no way to forget the annotation. A human token reaching one of these gets
     * 403, which is the correct answer: the caller is authenticated, and still
     * not the kind of caller this endpoint serves.</p>
     */
    public static final String[] INTERNAL_PATHS = {
            "/internal/**"
    };

    /**
     * Maps Keycloak's claims onto Spring authorities.
     *
     * <p>The resource id defaults to {@code spring.application.name}, which by
     * convention throughout this platform equals the service's Keycloak client
     * id. That is what lets a service pick its own client roles out of
     * {@code resource_access} and ignore roles granted against anyone else.</p>
     */
    @Bean
    public KeycloakAuthoritiesConverter keycloakAuthoritiesConverter(
            @Value("${platform.security.resource-id:${spring.application.name}}") String resourceId,
            ObjectProvider<PermissionCatalog> permissionCatalog) {
        // ObjectProvider, not a required dependency: a service that makes no
        // business authorization decision — the API gateway — imports this
        // configuration without importing PermissionSecurity, and still works.
        return new KeycloakAuthoritiesConverter(resourceId, permissionCatalog.getIfAvailable());
    }

    @Bean
    public PlatformJwtAuthenticationConverter platformJwtAuthenticationConverter(
            KeycloakAuthoritiesConverter authoritiesConverter) {
        return new PlatformJwtAuthenticationConverter(authoritiesConverter);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, PlatformJwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
            // CORS is handled once at the gateway, which is the only origin a
            // browser talks to. Services are not called cross-origin.
            .cors(AbstractHttpConfigurer::disable)

            // No cookies, no sessions, no CSRF tokens. The JWT in the
            // Authorization header is the only thing that authenticates a call,
            // and CSRF is an attack on ambient credentials such as cookies.
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            .authorizeHttpRequests(auth -> auth
                .requestMatchers(PUBLIC_PATHS).permitAll()
                // Service-to-service only. TOKEN_USE_SERVICE comes from the
                // token_use claim, so this is a claim check wearing the clothes
                // of an ordinary authority check.
                .requestMatchers(INTERNAL_PATHS).hasAuthority(CallerType.SERVICE.authority())
                .anyRequest().authenticated()
            )

            // Turn on JWT validation. Signature, issuer and expiry are checked
            // against keys fetched once from the realm and cached, so no call
            // leaves the service on the request path.
            //
            // The converter is what makes Keycloak roles visible to Spring.
            // Remove this one line and every hasRole(...) in the platform
            // becomes false, against tokens that plainly carry the role.
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));

        return http.build();
    }
}
