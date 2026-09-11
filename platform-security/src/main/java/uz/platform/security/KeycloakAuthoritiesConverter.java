package uz.platform.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Turns a Keycloak token into Spring Security authorities.
 *
 * <h2>Why this class has to exist</h2>
 *
 * <p>Spring Security's default, {@link JwtGrantedAuthoritiesConverter}, reads
 * exactly one claim: {@code scope}. It turns {@code "scope": "profile email"}
 * into {@code SCOPE_profile} and {@code SCOPE_email}. That is the OAuth2
 * standard, and Keycloak's roles are not part of it — {@code realm_access} is a
 * Keycloak invention that Spring cannot be expected to guess at.</p>
 *
 * <p>So without this converter the authority list contains no roles at all, every
 * {@code hasRole(...)} is false, and the API answers 403 while the token
 * decoded at jwt.io plainly shows the role. It is the most common
 * Keycloak-with-Spring failure, and it is a configuration gap rather than a bug.</p>
 *
 * <h2>What comes out</h2>
 *
 * <pre>
 * realm_access.roles         ["QURUVCHI"]      -> ROLE_QURUVCHI
 * resource_access.&lt;me&gt;.roles ["ORG_READ"]      -> ORG_READ
 * scope                      "profile email"   -> SCOPE_profile, SCOPE_email
 * token_use                  "user"            -> TOKEN_USE_USER
 * </pre>
 *
 * <h3>The ROLE_ prefix is not decorative</h3>
 *
 * <p>{@code hasRole("ADMIN")} is evaluated as {@code hasAuthority("ROLE_ADMIN")}.
 * Spring adds the prefix when <em>checking</em> and never when <em>building</em>,
 * so adding it is this converter's job. Keycloak roles are named without a
 * prefix precisely so that the mapping stays one readable line.</p>
 *
 * <h3>Why client roles get no prefix</h3>
 *
 * <p>Realm roles describe a person platform-wide and read naturally as roles.
 * Client roles here are machine permissions scoped to one service —
 * {@code ORG_READ} granted against {@code organization-service} — and they are
 * checked with {@code hasAuthority('ORG_READ')}. Writing that as
 * {@code hasRole('ORG_READ')} would suggest a business role, which it is not.</p>
 *
 * <p>Only roles granted against <b>this</b> service are mapped. A token carrying
 * {@code ORG_READ} for organization-service gives the caller nothing at
 * cadastral-service, which is what makes per-service scoping real rather than
 * advisory.</p>
 *
 * <p>Phase 5 extends this class: business roles will additionally be expanded
 * into the fine-grained CRUD permissions held in the application database.</p>
 */
public class KeycloakAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String ROLE_PREFIX = "ROLE_";

    /** Keeps the standard behaviour: scope claim to {@code SCOPE_*} authorities. */
    private final JwtGrantedAuthoritiesConverter scopeConverter = new JwtGrantedAuthoritiesConverter();

    /**
     * This service's Keycloak client id, used to pick its own client roles out of
     * {@code resource_access}. Defaults to {@code spring.application.name}, which
     * matches the client id by convention throughout this platform.
     */
    private final String resourceId;

    /**
     * Expands business roles into the fine-grained permissions this service
     * enforces. Null in a service that makes no business authorization decision,
     * such as the API gateway.
     */
    private final PermissionCatalog permissionCatalog;

    public KeycloakAuthoritiesConverter(String resourceId, PermissionCatalog permissionCatalog) {
        this.resourceId = resourceId;
        this.permissionCatalog = permissionCatalog;
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        // LinkedHashSet: de-duplicated, but stable order, so log lines and test
        // assertions read the same way every time.
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();

        Collection<GrantedAuthority> scopes = scopeConverter.convert(jwt);
        if (scopes != null) {
            authorities.addAll(scopes);
        }

        java.util.List<String> realmRoles = PlatformClaims.realmRoles(jwt);
        for (String realmRole : realmRoles) {
            authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + realmRole));
        }

        // Roles expand into permissions, resolved from the in-memory catalog.
        //
        // This is where "roles are not permissions" becomes concrete. The token
        // carries QURUVCHI; the database says QURUVCHI grants PROJECT_CREATE;
        // the authority list ends up with both. Because the expansion happens
        // per request rather than at token issue, granting a permission takes
        // effect on the next call instead of the next login — and the token
        // never grows as the platform gains resources.
        //
        // The union across roles is the entire rule: someone holding QURUVCHI
        // and BANK gets everything either role allows.
        if (permissionCatalog != null) {
            for (String permission : permissionCatalog.permissionsFor(realmRoles)) {
                authorities.add(new SimpleGrantedAuthority(permission));
            }
        }

        for (String clientRole : PlatformClaims.clientRoles(jwt, resourceId)) {
            authorities.add(new SimpleGrantedAuthority(clientRole));
        }

        // Makes "is this a person or a machine" an ordinary authority, so it can
        // be enforced by a URL rule with no custom AuthorizationManager.
        authorities.add(new SimpleGrantedAuthority(CallerType.of(jwt).authority()));

        return authorities;
    }
}
