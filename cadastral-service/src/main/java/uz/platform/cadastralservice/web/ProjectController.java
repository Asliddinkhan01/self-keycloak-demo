package uz.platform.cadastralservice.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import uz.platform.cadastralservice.domain.Project;
import uz.platform.cadastralservice.repo.ProjectRepository;

/**
 * Phase 3: an authenticated read, and nothing more.
 *
 * <p>By phase 6 this endpoint will require the PROJECT_READ permission and an
 * X-Organization-TIN header validated against the caller's memberships, and will
 * return only that organization's projects. Today it returns everything to any
 * valid token, which is exactly the gap the next phases close.</p>
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectRepository projects;

    public ProjectController(ProjectRepository projects) {
        this.projects = projects;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return projects.findAllByOrderByCreatedAtDesc().stream().map(ProjectController::toView).toList();
    }

    private static Map<String, Object> toView(Project project) {
        return Map.of(
                "id", project.getId(),
                "name", project.getName(),
                "address", String.valueOf(project.getAddress()),
                "status", project.getStatus(),
                "organizationTin", project.getOrganizationTin());
    }
}
