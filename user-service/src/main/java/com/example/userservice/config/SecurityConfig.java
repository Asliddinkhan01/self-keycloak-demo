package com.example.userservice.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Security for user-service.
 *
 * <p>This service is an OAuth2 RESOURCE SERVER: it never logs anybody in. It only
 * accepts an already-issued access token in the Authorization header and decides
 * whether the caller may proceed.</p>
 *
 * <p>Authorization here is expressed with URL matchers. product-service shows the
 * other style, method-level PreAuthorize annotations.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final String allowedOrigin;

    public SecurityConfig(@Value("${app.cors.allowed-origin}") String allowedOrigin) {
        this.allowedOrigin = allowedOrigin;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Browser calls come from http://localhost:5173, a different origin
            // than http://localhost:8081, so CORS must be enabled explicitly.
            .cors(Customizer.withDefaults())

            // No cookies, no sessions, no CSRF tokens: the JWT in the
            // Authorization header is the only thing that authenticates a call.
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            .authorizeHttpRequests(auth -> auth
                // Reading your own profile needs the USER role.
                .requestMatchers(HttpMethod.GET, "/api/users/me").hasRole("USER")
                .requestMatchers(HttpMethod.GET, "/api/users/me/claims").hasRole("USER")
                // Listing everybody is an administrative action.
                .requestMatchers(HttpMethod.GET, "/api/users").hasRole("ADMIN")
                // Anything else at least needs a valid token.
                .anyRequest().authenticated()
            )

            // Turn on JWT validation. The issuer-uri in application.yml tells
            // Spring where to fetch Keycloak's public keys (JWKS) from.
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
            );

        return http.build();
    }

    /**
     * Wires the Keycloak-aware role converter into Spring Security and makes
     * Authentication.getName() return the Keycloak username instead of the
     * opaque "sub" UUID.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        converter.setPrincipalClaimName("preferred_username");
        return converter;
    }

    /**
     * CORS is a BROWSER rule, not a server security feature. A browser refuses to
     * hand a cross-origin response back to JavaScript unless the server says
     * "this origin is allowed". curl and Postman ignore CORS completely, which is
     * why an endpoint can work in curl and still fail in the Vue app.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(allowedOrigin));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        // Without "Authorization" here the browser blocks the preflight request
        // and the Bearer token never reaches this service.
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
