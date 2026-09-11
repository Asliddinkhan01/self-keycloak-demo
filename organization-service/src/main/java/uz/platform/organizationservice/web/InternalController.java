package uz.platform.organizationservice.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import uz.platform.organizationservice.service.MembershipService;
import uz.platform.security.CallerType;
import uz.platform.security.PlatformClaims;

/**
 * Endpoints other services call. Never a browser.
 *
 * <p>Access is enforced by the {@code /internal/**} rule in the shared
 * {@code ResourceServerSecurity}: a caller must present a token whose
 * {@code token_use} is {@code service}. A perfectly valid human token gets 403
 * here — authenticated, and still the wrong kind of caller.</p>
 *

 */
@RestController
@RequestMapping("/internal")
public class InternalController {

    private final MembershipService memberships;

    public InternalController(MembershipService memberships) {
        this.memberships = memberships;
    }

    /**
     * Reports what the service token actually carries.
     *
     * <p>Useful as a diagnostic: it shows the audience the token was minted for
     * and the client roles granted against <em>this</em> service, which is how
     * least privilege is verified rather than assumed.</p>
     */
    @GetMapping("/ping")
    public Map<String, Object> ping(@AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        return Map.of(
                "service", "organization-service",
                "callerType", CallerType.of(jwt).name(),
                "callingClient", String.valueOf(jwt.getClaimAsString(PlatformClaims.AUTHORIZED_PARTY)),
                "principal", authentication.getName(),
                "audience", jwt.getAudience(),
                "rolesGrantedHere", PlatformClaims.clientRoles(jwt, "organization-service"));
    }

    /**
     * The active memberships of one person, for another service to check against.
     *
     * <p>This is the endpoint that makes organization context work platform-wide.
     * cadastral-service cannot read {@code user_organizations} — its PostgreSQL
     * role has no privileges on this schema — so it asks here, with its own
     * service token, and caches the answer briefly.</p>
     *
     * <p>Two protections apply, and both are needed. The {@code /internal/**}
     * rule requires a service token, so no browser can call it. The
     * {@code ORG_READ} client role means only services actually granted that
     * permission get an answer; a service account without it is refused even
     * though it authenticated perfectly.</p>
     *
     * <p>It returns TINs and nothing else. A caller asking "may this person act
     * for company X" does not need names, addresses or anything about the
     * person, so none is sent.</p>
     */
    @PreAuthorize("hasAuthority('ORG_READ')")
    @GetMapping("/memberships/{keycloakSub}")
    public Map<String, Object> memberships(@PathVariable UUID keycloakSub) {
        return Map.of(
                "keycloakSub", keycloakSub,
                "activeTins", memberships.activeTins(keycloakSub));
    }

    /**
     * Writes memberships from what OneID reported. The only write path to
     * {@code user_organizations}.
     *
     * <p>Guarded by a <b>different</b> client role from the read above:
     * {@code ORG_MEMBERSHIP_SYNC}. That is the point of the endpoint as much as
     * its function. user-service holds it because bootstrapping a session is its
     * job; cadastral-service does not, because reading whether someone belongs to
     * a company is all it ever needs. Two service accounts, two different sets of
     * privileges, both narrower than "trusted service".</p>
     *
     * <p>The TINs come in the body rather than being read from the caller's token,
     * because the caller is a machine and the token describes the machine, not the
     * person being reconciled. user-service takes them from the human's verified
     * token and passes them on.</p>
     */
    @PreAuthorize("hasAuthority('ORG_MEMBERSHIP_SYNC')")
    @PostMapping("/memberships/{keycloakSub}/sync")
    public Map<String, Object> sync(@PathVariable UUID keycloakSub,
                                    @RequestBody SyncRequest request) {
        List<String> active = memberships.reconcile(keycloakSub, request.tins());
        return Map.of(
                "keycloakSub", keycloakSub,
                "reported", request.tins(),
                "activeTins", active);
    }

    /** TINs OneID reported for this person, taken from their {@code org_tins} claim. */
    public record SyncRequest(List<String> tins) {

        public List<String> tins() {
            return tins == null ? List.of() : tins;
        }
    }
}
