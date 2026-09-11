package uz.platform.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The names of every claim this platform reads, in one place, plus null-safe
 * accessors for them.
 *
 * <p>Claim names are a contract between Keycloak's realm configuration and the
 * services. Spelling one of them slightly wrong in a controller produces a
 * silent null rather than a compile error, so they are declared once here and
 * never written as string literals anywhere else.</p>
 */
public final class PlatformClaims {

    /** Keycloak's stable user id. The join key between the token and every service database. */
    public static final String SUBJECT = "sub";

    /** The OneID login, mapped to the Keycloak username. Never the PIN. */
    public static final String PREFERRED_USERNAME = "preferred_username";

    /** Keycloak realm roles live here, nested: {@code realm_access.roles}. */
    public static final String REALM_ACCESS = "realm_access";

    /** Client roles live here, keyed by client id: {@code resource_access.<client>.roles}. */
    public static final String RESOURCE_ACCESS = "resource_access";

    /**
     * Authorized party: the client the token was issued to. On a service token
     * this names the calling service, which makes it the natural fallback
     * principal name when there is no human username.
     */
    public static final String AUTHORIZED_PARTY = "azp";

    public static final String ROLES = "roles";

    /**
     * Set by a hardcoded protocol mapper on every client: {@code user} or {@code service}.
     * Making the distinction explicit means a service never has to infer caller type
     * from Keycloak internals, which vary between releases.
     */
    public static final String TOKEN_USE = "token_use";

    /** Taxpayer numbers of the organizations OneID reported. Seeds membership; never the decision. */
    public static final String ORG_TINS = "org_tins";

    /** Whether OneID reported "Tasdiqlangan foydalanuvchi" status, from its {@code valid} field. */
    public static final String IDENTITY_VERIFIED = "identity_verified";

    /** OneID {@code user_type}: {@code I} physical person, {@code L} legal entity. */
    public static final String USER_TYPE = "user_type";

    private PlatformClaims() {
    }

    /** Keycloak's user id as a UUID. Present on human tokens and on service-account tokens alike. */
    public static UUID subject(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    public static String username(Jwt jwt) {
        return jwt.getClaimAsString(PREFERRED_USERNAME);
    }

    /** Realm roles, or an empty list. Never null, so callers need no guard. */
    public static List<String> realmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap(REALM_ACCESS);
        if (realmAccess != null && realmAccess.get(ROLES) instanceof Collection<?> roles) {
            return roles.stream().map(String::valueOf).sorted().toList();
        }
        return List.of();
    }

    /** Roles granted to a service account against one specific client, or an empty list. */
    @SuppressWarnings("unchecked")
    public static List<String> clientRoles(Jwt jwt, String clientId) {
        Map<String, Object> resourceAccess = jwt.getClaimAsMap(RESOURCE_ACCESS);
        if (resourceAccess != null && resourceAccess.get(clientId) instanceof Map<?, ?> client
                && ((Map<String, Object>) client).get(ROLES) instanceof Collection<?> roles) {
            return roles.stream().map(String::valueOf).sorted().toList();
        }
        return List.of();
    }

    public static List<String> orgTins(Jwt jwt) {
        List<String> tins = jwt.getClaimAsStringList(ORG_TINS);
        return tins == null ? List.of() : tins;
    }

    public static boolean identityVerified(Jwt jwt) {
        return Boolean.TRUE.equals(jwt.getClaim(IDENTITY_VERIFIED));
    }

    public static String userType(Jwt jwt) {
        return jwt.getClaimAsString(USER_TYPE);
    }
}
