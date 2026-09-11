package uz.platform.organizationservice.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import uz.platform.organizationservice.domain.Organization;
import uz.platform.organizationservice.repo.OrganizationRepository;

/**
 * Phase 3: authenticated reads only.
 *
 * <p>Permission checks arrive in phase 5 and the internal, service-token-only
 * membership endpoint in phase 6. Today these endpoints prove the one thing
 * phase 3 is about: no token gives 401, a valid Keycloak token gives 200.</p>
 */
@RestController
@RequestMapping("/api/organizations")
public class OrganizationController {

    private final OrganizationRepository organizations;

    public OrganizationController(OrganizationRepository organizations) {
        this.organizations = organizations;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return organizations.findAllByOrderByTinAsc().stream().map(OrganizationController::toView).toList();
    }

    @GetMapping("/{tin}")
    public ResponseEntity<Map<String, Object>> byTin(@PathVariable String tin) {
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
}
