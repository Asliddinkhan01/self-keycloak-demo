package uz.platform.security;

import java.util.Collection;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Builds the {@link org.springframework.security.core.Authentication} that the
 * rest of the request sees.
 *
 * <p>Spring's own {@code JwtAuthenticationConverter} would do, but its principal
 * name comes from a single configured claim and it has no fallback. Service
 * tokens and human tokens do not carry the same claims, and a null principal
 * name is the kind of defect that only shows up in an audit log months later,
 * so the name is resolved explicitly here.</p>
 */
public class PlatformJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final Converter<Jwt, Collection<GrantedAuthority>> authoritiesConverter;

    public PlatformJwtAuthenticationConverter(Converter<Jwt, Collection<GrantedAuthority>> authoritiesConverter) {
        this.authoritiesConverter = authoritiesConverter;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        return new JwtAuthenticationToken(jwt, authoritiesConverter.convert(jwt), principalName(jwt));
    }

    /**
     * What {@code authentication.getName()} returns, and therefore what lands in
     * audit rows and log lines.
     *
     * <ol>
     *   <li>{@code preferred_username} — the OneID login for a person, and
     *       {@code service-account-<client>} for a service account.</li>
     *   <li>{@code azp} — the calling client, if a token somehow has no username.</li>
     *   <li>{@code sub} — always present, but an opaque UUID, so it is the last
     *       resort rather than the default.</li>
     * </ol>
     *
     * <p>Note what is deliberately never used: the PIN. It is not in the token at
     * all, and a national identification number has no business appearing in a
     * log file.</p>
     */
    private static String principalName(Jwt jwt) {
        String username = jwt.getClaimAsString(PlatformClaims.PREFERRED_USERNAME);
        if (username != null && !username.isBlank()) {
            return username;
        }
        String authorizedParty = jwt.getClaimAsString(PlatformClaims.AUTHORIZED_PARTY);
        if (authorizedParty != null && !authorizedParty.isBlank()) {
            return authorizedParty;
        }
        return jwt.getSubject();
    }
}
