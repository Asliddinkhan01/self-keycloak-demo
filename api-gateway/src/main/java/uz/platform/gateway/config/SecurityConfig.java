package uz.platform.gateway.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import uz.platform.security.ResourceServerSecurity;

/**
 * Gateway security: validate at the edge, decide nothing.
 *
 * <h2>What the gateway does</h2>
 *
 * <p>It checks that a token is signed by the right realm, addressed to this
 * platform, and unexpired, and it rejects anonymous requests before they travel
 * any further. It owns CORS, because it is the only process a browser talks to.
 * Then it forwards the request, <b>token unchanged</b>.</p>
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>No business authorization. It imports {@link ResourceServerSecurity} but not
 * {@code PermissionSecurity}, has no database, and has no idea what
 * {@code PROJECT_CREATE} means. Putting permission checks here would put business
 * rules in two places and make the gateway the thing everyone edits.</p>
 *
 * <p>It also never replaces the token with headers such as {@code X-User-Id}.
 * That would turn a signed, verifiable assertion into a forgeable string and make
 * every downstream service dependent on the gateway being the only possible
 * caller — which is exactly the assumption that fails the day something reaches a
 * service directly.</p>
 *
 * <h2>Why services validate again anyway</h2>
 *
 * <p>Edge validation is a filter, not a guarantee. A misrouted internal call, a
 * port-forward during debugging, a compromised neighbour, or a future
 * infrastructure change can all reach a service without passing through here.
 * Every service therefore proves for itself who is calling. The two checks are
 * not redundant; they defend different things.</p>
 */
@Configuration
@Import(ResourceServerSecurity.class)
public class SecurityConfig {

    private final String allowedOrigin;

    public SecurityConfig(@Value("${app.cors.allowed-origin}") String allowedOrigin) {
        this.allowedOrigin = allowedOrigin;
    }

    /**
     * CORS is a BROWSER rule, not a server access control.
     *
     * <p>A browser refuses to hand a cross-origin response back to JavaScript
     * unless the server says the origin is allowed. curl and Postman ignore it
     * entirely, which is why an endpoint can work in curl and still fail in the
     * Vue app — and why CORS is never a substitute for authorization.</p>
     *
     * <p>Declaring this bean is also what switches CORS on in the shared filter
     * chain: services that declare none get it disabled, because they are never
     * called cross-origin.</p>
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(allowedOrigin));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        // Authorization must be allowed or the browser blocks the preflight and
        // the token never arrives. X-Organization-TIN is this platform's own
        // header and needs naming for the same reason.
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Organization-TIN"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
