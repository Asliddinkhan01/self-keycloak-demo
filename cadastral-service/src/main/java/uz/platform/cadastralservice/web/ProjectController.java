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
import uz.platform.security.PlatformClaims;

/**
 * Projects, gated by the permissions defined in this service's own migration.
 *
 * <pre>
 * GET    /api/projects        PROJECT_READ     JISMONIY_SHAXS, YURIDIK_SHAXS, QURUVCHI
 * POST   /api/projects        PROJECT_CREATE   QURUVCHI only
 * PUT    /api/projects/{id}   PROJECT_UPDATE   QURUVCHI only
 * </pre>
 *
 * <p>BANK holds no {@code PROJECT_*} permission at all, so a bank operator with a
 * perfectly valid token gets 403 here. That is the difference between
 * authentication and authorization in one HTTP status code.</p>
 *
 * <p>Nothing in this controller mentions a role. It names the capability it
 * needs and lets the database decide who has it.</p>
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectRepository projects;

    public ProjectController(ProjectRepository projects) {
        this.projects = projects;
    }

    @PreAuthorize("@permissionChecker.has(authentication, 'PROJECT_READ')")
    @GetMapping
    public List<Map<String, Object>> list() {
        return projects.findAllByOrderByCreatedAtDesc().stream().map(ProjectController::toView).toList();
    }

    /**
     * QURUVCHI only.
     *
     * <p>The creator is taken from the verified token, never from the request
     * body. A caller cannot claim to be someone else, because the value comes
     * out of a signature Keycloak produced.</p>
     */
    @PreAuthorize("@permissionChecker.has(authentication, 'PROJECT_CREATE')")
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateProjectRequest request,
                                                      @AuthenticationPrincipal Jwt jwt) {
        Project created = projects.save(new Project(
                request.name(),
                request.address(),
                request.organizationTin(),
                PlatformClaims.subject(jwt)));
        return ResponseEntity.status(HttpStatus.CREATED).body(toView(created));
    }

    @PreAuthorize("@permissionChecker.has(authentication, 'PROJECT_UPDATE')")
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable UUID id,
                                                      @Valid @RequestBody CreateProjectRequest request) {
        return projects.findById(id)
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
