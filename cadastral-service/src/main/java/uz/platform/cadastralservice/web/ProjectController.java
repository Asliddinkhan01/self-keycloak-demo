package uz.platform.cadastralservice.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import uz.platform.cadastralservice.domain.Project;
import uz.platform.cadastralservice.dto.CreateProjectRequest;
import uz.platform.cadastralservice.repo.ProjectRepository;
import uz.platform.security.OrganizationContext;
import uz.platform.security.PlatformClaims;

/**
 * Projects, scoped to the organization the caller is acting for.
 *
 * <p>Every endpoint here now answers three questions in order, and all three must
 * pass:</p>
 *
 * <ol>
 *   <li><b>Who are you?</b> The JWT signature, issuer and expiry, checked by the
 *       resource server.</li>
 *   <li><b>May you do this?</b> The permission, resolved from the caller's roles
 *       through this service's own {@code role_permissions} table.</li>
 *   <li><b>On whose behalf?</b> The {@code X-Organization-TIN} header, verified
 *       against {@code user_organizations} before the controller runs.</li>
 * </ol>
 *
 * <p>The third is the one that is easy to get wrong. A caller holding
 * {@code PROJECT_CREATE} is entitled to create projects — but only for
 * organizations they actually belong to. Permission and context are independent
 * questions, and answering only the first would let any builder file documents
 * against any company in the country.</p>
 *
 * <p>Notice that no method reads an organization from the request body. The TIN
 * comes from {@link OrganizationContext}, which the filter populates only after
 * confirming membership, so by the time this code runs the value is already
 * trustworthy.</p>
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectRepository projects;
    private final OrganizationContext organizationContext;

    public ProjectController(ProjectRepository projects, OrganizationContext organizationContext) {
        this.projects = projects;
        this.organizationContext = organizationContext;
    }

    /**
     * Projects of the acting organization.
     *
     * <p>Scoping the query by the verified TIN is not a convenience, it is the
     * authorization. Returning every project and letting the UI filter would
     * leak one company's construction records to another.</p>
     */
    @PreAuthorize("@permissionChecker.has(authentication, 'PROJECT_READ')")
    @GetMapping
    public List<Map<String, Object>> list() {
        String tin = organizationContext.requireTin();
        return projects.findByOrganizationTinOrderByCreatedAtDesc(tin).stream()
                .map(ProjectController::toView)
                .toList();
    }

    /**
     * QURUVCHI only, and only for an organization the caller belongs to.
     *
     * <p>Both the creator and the organization come from verified sources: the
     * subject from the signed token, the TIN from the membership check. Neither
     * can be set by the client.</p>
     */
    @PreAuthorize("@permissionChecker.has(authentication, 'PROJECT_CREATE')")
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateProjectRequest request,
                                                      @AuthenticationPrincipal Jwt jwt) {
        Project created = projects.save(new Project(
                request.name(),
                request.address(),
                organizationContext.requireTin(),
                PlatformClaims.subject(jwt)));
        return ResponseEntity.status(HttpStatus.CREATED).body(toView(created));
    }

    /**
     * QURUVCHI only, and only on a project belonging to the acting organization.
     *
     * <p>A project outside that organization is reported as 404 rather than 403.
     * Saying "this exists but is not yours" would confirm the existence of
     * another company's records to someone with no right to know.</p>
     */
    @PreAuthorize("@permissionChecker.has(authentication, 'PROJECT_UPDATE')")
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable UUID id,
                                                      @Valid @RequestBody CreateProjectRequest request) {
        String tin = organizationContext.requireTin();
        return projects.findById(id)
                .filter(project -> project.getOrganizationTin().equals(tin))
                .map(project -> {
                    project.rename(request.name(), request.address());
                    return ResponseEntity.ok(toView(projects.save(project)));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static Map<String, Object> toView(Project project) {
        return Map.of(
                "id", project.getId(),
                "name", project.getName(),
                "address", String.valueOf(project.getAddress()),
                "status", project.getStatus(),
                "organizationTin", project.getOrganizationTin(),
                "createdBySub", project.getCreatedBySub());
    }
}
