package uz.platform.userservice.web;

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import uz.platform.security.PermissionCatalog;
import uz.platform.security.PlatformClaims;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final PermissionCatalog permissionCatalog;

    public UserController(PermissionCatalog permissionCatalog) {
        this.permissionCatalog = permissionCatalog;
    }

    /**
     * Any authenticated caller.
     *
     * <p>Nothing here reads a database yet: every field comes from the JWT that
     * Keycloak signed, and Spring verified the signature, issuer and expiry
     * before this method was reached, so the claims can be trusted.</p>
     *
     * <p>The response shows the raw realm roles next to the Spring authorities.
     * Before phase 4 they disagreed: the token carried three roles and the
     * authority list held only {@code SCOPE_*}, because Spring's default
     * converter reads only the {@code scope} claim. With
     * {@code KeycloakAuthoritiesConverter} in place they now line up, and this
     * is the endpoint where that is visible.</p>
     *
     * <p>Restricted to human callers. A service token is authenticated and still
     * refused with 403, because "my profile" is meaningless for a machine — it
     * has no person behind it to describe.</p>
     */
    @PreAuthorize("hasAuthority('TOKEN_USE_USER')")
    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        return Map.of(
                "subject", PlatformClaims.subject(jwt),
                "username", String.valueOf(PlatformClaims.username(jwt)),
                "tokenUse", String.valueOf(jwt.getClaimAsString(PlatformClaims.TOKEN_USE)),
                "identityVerified", PlatformClaims.identityVerified(jwt),
                "userType", String.valueOf(PlatformClaims.userType(jwt)),
                "organizationTins", PlatformClaims.orgTins(jwt),
                "realmRolesFromToken", PlatformClaims.realmRoles(jwt),
                "effectivePermissionsHere", permissionCatalog.permissionsFor(PlatformClaims.realmRoles(jwt)),
                "springAuthorities", authorities(authentication),
                "issuer", String.valueOf(jwt.getIssuer()));
    }

    /**
     * Every claim of the access token.
     *
     * <p>Useful while learning: call it and compare with the same token decoded
     * at jwt.io. Note that no PIN and no passport number appear, because neither
     * is ever put into a token.</p>
     */
    @GetMapping("/me/claims")
    public Map<String, Object> claims(@AuthenticationPrincipal Jwt jwt) {
        return jwt.getClaims();
    }

    private static List<String> authorities(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .sorted()
                .toList();
    }
}
