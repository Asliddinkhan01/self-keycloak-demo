package uz.platform.organizationservice.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import uz.platform.organizationservice.domain.Organization;
import uz.platform.organizationservice.domain.UserOrganization;
import uz.platform.organizationservice.repo.OrganizationRepository;
import uz.platform.organizationservice.service.MembershipService;
import uz.platform.security.OrganizationContext;
import uz.platform.security.PlatformClaims;

@RestController
@RequestMapping("/api/organizations")
public class OrganizationController {

    private final OrganizationRepository organizations;
    private final MembershipService memberships;
    private final OrganizationContext organizationContext;

    public OrganizationController(OrganizationRepository organizations,
                                  MembershipService memberships,
                                  OrganizationContext organizationContext) {
        this.organizations = organizations;
        this.memberships = memberships;
        this.organizationContext = organizationContext;
    }

    @PreAuthorize("@permissionChecker.has(authentication, 'ORGANIZATION_READ')")
    @GetMapping
    public List<Map<String, Object>> list() {
        return organizations.findAllByOrderByTinAsc().stream().map(OrganizationController::toView).toList();
    }

    @PreAuthorize("@permissionChecker.has(authentication, 'ORGANIZATION_READ')")
    @GetMapping("/{tin}")
    public ResponseEntity<Map<String, Object>> byTin(@PathVariable String tin) {
        return organizations.findByTin(tin)
                .map(OrganizationController::toView)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * The caller's own organizations. Read-only.
     *
     * <p>Any authenticated person, no permission needed: asking which companies
     * you belong to is not a privileged act.</p>
     *
     * <p>This endpoint does <b>not</b> reconcile. Writing memberships happens in
     * exactly one place, the service-only sync endpoint, because two write paths
     * to the same table is how they drift apart. A session that has never been
     * bootstrapped therefore sees an empty list, which is honest: nothing has
     * told this service what OneID reported yet.</p>
     */
    @GetMapping("/mine")
    public Map<String, Object> mine(@AuthenticationPrincipal Jwt jwt) {
        List<Map<String, Object>> mine = memberships.activeMemberships(PlatformClaims.subject(jwt)).stream()
                .map(OrganizationController::toMembershipView)
                .toList();

        return Map.of(
                "activeMemberships", mine,
                "note", "Read-only. Memberships are written by user-service through "
                        + "/internal/memberships/{sub}/sync, never from a browser.");
    }

    /**
     * The organization the caller is currently acting for.
     *
     * <p>Requires the {@code X-Organization-TIN} header, and the filter has
     * already confirmed membership by the time this runs — so reaching this
     * method at all is proof the caller is entitled to that organization.</p>
     */
    @PreAuthorize("@permissionChecker.has(authentication, 'ORGANIZATION_READ')")
    @GetMapping("/current")
    public ResponseEntity<Map<String, Object>> current() {
        String tin = organizationContext.requireTin();
        return organizations.findByTin(tin)
                .map(OrganizationController::toView)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static Map<String, Object> toView(Organization organization) {
        return Map.of(
                "tin", organization.getTin(),
                "name", organization.getName(),
                "shortName", String.valueOf(organization.getShortName()),
                "active", organization.isActive());
    }

    private static Map<String, Object> toMembershipView(UserOrganization membership) {
        return Map.of(
                "tin", membership.getOrganization().getTin(),
                "name", membership.getOrganization().getName(),
                "shortName", String.valueOf(membership.getOrganization().getShortName()),
                "isBasic", membership.isBasic(),
                "source", membership.getSource());
    }
}
