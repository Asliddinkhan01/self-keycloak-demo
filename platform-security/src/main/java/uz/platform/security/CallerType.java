package uz.platform.security;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Who is calling: a person, or another service.
 *
 * <p>Every token in this platform carries a {@code token_use} claim, set by a
 * hardcoded protocol mapper on each Keycloak client. That is deliberate. The
 * alternative is to infer caller type from Keycloak internals — the presence of
 * a session claim, the shape of {@code preferred_username}, whether
 * {@code realm_access} is empty — and every one of those details has changed
 * between Keycloak releases. A claim the realm sets explicitly is a contract;
 * an inference is a guess that breaks on upgrade.</p>
 *
 * <p>The enum is turned into a granted authority so that the distinction can be
 * enforced with an ordinary URL rule or {@code hasAuthority(...)} expression,
 * with no custom {@code AuthorizationManager} anywhere:</p>
 *
 * <pre>
 * human token   -> TOKEN_USE_USER
 * service token -> TOKEN_USE_SERVICE
 * neither       -> TOKEN_USE_UNKNOWN
 * </pre>
 *
 * <p>{@link #UNKNOWN} exists so the mapping is total and fails closed. A token
 * with no {@code token_use} matches no rule that requires a specific caller
 * type, so it can never reach an internal endpoint by accident.</p>
 */
public enum CallerType {

    /** A human, authenticated through OneID and issued a token by Keycloak. */
    USER("user"),

    /** A service account, via the client-credentials grant. No person involved. */
    SERVICE("service"),

    /** The claim was missing or unrecognised. Treated as neither. */
    UNKNOWN(null);

    private final String claimValue;

    CallerType(String claimValue) {
        this.claimValue = claimValue;
    }

    /** The authority this caller type grants, for example {@code TOKEN_USE_SERVICE}. */
    public String authority() {
        return "TOKEN_USE_" + name();
    }

    public static CallerType of(Jwt jwt) {
        String value = jwt.getClaimAsString(PlatformClaims.TOKEN_USE);
        for (CallerType type : values()) {
            if (type.claimValue != null && type.claimValue.equals(value)) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
