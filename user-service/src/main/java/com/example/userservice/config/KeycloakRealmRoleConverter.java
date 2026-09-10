package com.example.userservice.config;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.core.convert.converter.Converter;
import org.springframework.lang.NonNull;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Turns Keycloak realm roles into Spring Security authorities.
 *
 * <p>WHY THIS CLASS EXISTS</p>
 *
 * Keycloak puts realm roles into a nested, Keycloak-specific claim:
 *
 * <pre>
 * {
 *   "realm_access": { "roles": ["USER", "ADMIN"] },
 *   "scope": "openid profile email"
 * }
 * </pre>
 *
 * Spring Security knows nothing about {@code realm_access}. Out of the box its
 * {@link JwtGrantedAuthoritiesConverter} only reads the {@code scope} (or
 * {@code scp}) claim and produces authorities like {@code SCOPE_profile}.
 * So without this class the authority list would contain no roles at all and
 * {@code hasRole("ADMIN")} would always be false.
 *
 * <p>THE ROLE_ PREFIX</p>
 *
 * {@code hasRole("ADMIN")} is just sugar for {@code hasAuthority("ROLE_ADMIN")}.
 * Spring Security adds the {@code ROLE_} prefix for you when it evaluates the
 * expression, but it never adds it when it builds authorities. That is our job
 * here: Keycloak role {@code ADMIN} becomes authority {@code ROLE_ADMIN}.
 *
 * <p>The full chain is:</p>
 *
 * <pre>
 * Keycloak role ADMIN
 *      -> JWT claim realm_access.roles = ["ADMIN"]
 *      -> KeycloakRealmRoleConverter
 *      -> GrantedAuthority("ROLE_ADMIN")
 *      -> hasRole("ADMIN") == true
 * </pre>
 */
public class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String REALM_ACCESS_CLAIM = "realm_access";
    private static final String ROLES_KEY = "roles";
    private static final String ROLE_PREFIX = "ROLE_";

    /** Keeps the default behavior: scope claim -> SCOPE_xxx authorities. */
    private final JwtGrantedAuthoritiesConverter scopeConverter = new JwtGrantedAuthoritiesConverter();

    @Override
    public Collection<GrantedAuthority> convert(@NonNull Jwt jwt) {

        Collection<GrantedAuthority> scopes = scopeConverter.convert(jwt);
        Set<GrantedAuthority> authorities = new HashSet<>(scopes);

        // realm_access.roles -> ROLE_xxx
        Map<String, Object> realmAccess = jwt.getClaimAsMap(REALM_ACCESS_CLAIM);
        if (realmAccess != null && realmAccess.get(ROLES_KEY) instanceof Collection<?> roles) {
            for (Object role : roles) {
                authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + role));
            }
        }

        return authorities;
    }
}
