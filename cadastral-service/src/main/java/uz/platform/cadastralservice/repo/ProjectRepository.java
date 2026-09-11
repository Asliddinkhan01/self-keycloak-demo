package uz.platform.cadastralservice.repo;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import uz.platform.cadastralservice.domain.Project;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    List<Project> findByOrganizationTinOrderByCreatedAtDesc(String organizationTin);

    List<Project> findAllByOrderByCreatedAtDesc();
}
