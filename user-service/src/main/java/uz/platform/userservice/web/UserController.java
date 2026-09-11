package uz.platform.userservice.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import uz.platform.security.PermissionCatalog;
import uz.platform.security.PlatformClaims;
import uz.platform.userservice.client.OrganizationClient;
import uz.platform.userservice.domain.AppUser;
import uz.platform.userservice.repo.AppUserRepository;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final PermissionCatalog permissionCatalog;
    private final AppUserRepository users;
    private final OrganizationClient organizations;

    public UserController(PermissionCatalog permissionCatalog,
                          AppUserRepository users,
                          OrganizationClient organizations) {
        this.permissionCatalog = permissionCatalog;
        this.users = users;
        this.organizations = organizations;
    }

    /**
     * Session bootstrap: everything the frontend needs after login, in one call.
     *
     * <p>Three things happen here, and the order matters:</p>
     *
     * <ol>
     *   <li><b>The local profile is created or refreshed</b> from the verified
     *       token. OneID is authoritative for names, so this row is a cache and
     *       is overwritten each time. Roles are never touched — those belong to
     *       the platform, and overwriting them would silently revoke privileges
     *       an administrator granted.</li>
     *   <li><b>Memberships are reconciled</b> by calling organization-service as
     *       a service. This is the only write path to {@code user_organizations},
     *       and it needs the {@code ORG_MEMBERSHIP_SYNC} client role.</li>
     *   <li><b>Roles, permissions and organizations are returned together</b>, so
     *       the UI can render its organization switcher and hide actions the
     *       person cannot perform.</li>
     * </ol>
     *
     * <p>Hiding actions in the UI is a convenience, never the control. Every
     * endpoint checks the permission again on the server, because a hidden button
     * is one curl command away from being pressed anyway.</p>
     *
     * <p>Human callers only. A service token is refused: "my profile" is
     * meaningless for a machine that has no person behind it.</p>
     */
    @PreAuthorize("hasAuthority('TOKEN_USE_USER')")
    @Transactional
    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        UUID subject = PlatformClaims.subject(jwt);
        AppUser profile = upsertProfile(jwt, subject);
        List<String> organizationTins = organizations.syncMemberships(subject, PlatformClaims.orgTins(jwt));

        // LinkedHashMap rather than Map.of: this response has outgrown the ten
        // pairs Map.of supports, and insertion order makes it readable.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subject", subject);
        body.put("username", PlatformClaims.username(jwt));
        body.put("localProfileId", profile.getId());
        body.put("fullName", profile.getFullName());
        body.put("userType", PlatformClaims.userType(jwt));
        body.put("identityVerified", PlatformClaims.identityVerified(jwt));
        body.put("tokenUse", jwt.getClaimAsString(PlatformClaims.TOKEN_USE));
        body.put("realmRolesFromToken", PlatformClaims.realmRoles(jwt));
        body.put("effectivePermissionsHere", permissionCatalog.permissionsFor(PlatformClaims.realmRoles(jwt)));
        body.put("springAuthorities", authorities(authentication));
        body.put("organizationTinsReportedByOneId", PlatformClaims.orgTins(jwt));
        body.put("organizationTinsActive", organizationTins);
        body.put("issuer", String.valueOf(jwt.getIssuer()));
        return body;
    }

    /**
     * Just-in-time provisioning.
     *
     * <p>Keycloak does not know this service exists, so nothing creates a profile
     * row when someone registers. It is created the first time that person calls
     * an endpoint, keyed on the token's {@code sub} — not on the username, which
     * an administrator can change, and not on the PIN, which never leaves
     * Keycloak.</p>
     */
    private AppUser upsertProfile(Jwt jwt, UUID subject) {
        AppUser profile = users.findByKeycloakSub(subject)
                .orElseGet(() -> new AppUser(subject, PlatformClaims.username(jwt)));

        profile.refreshFrom(
                PlatformClaims.username(jwt),
                jwt.getClaimAsString("name"),
                jwt.getClaimAsString("given_name"),
                jwt.getClaimAsString("family_name"),
                PlatformClaims.userType(jwt),
                PlatformClaims.identityVerified(jwt));

        return users.save(profile);
    }

    /**
     * Every claim of the access token.
     *
     * <p>Useful while learning: call it and compare with the same token decoded
     * at jwt.io. Note that no PIN and no passport number appear, because neither
     * is ever put into a token.</p>
     */
    @PreAuthorize("hasAuthority('TOKEN_USE_USER')")
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
