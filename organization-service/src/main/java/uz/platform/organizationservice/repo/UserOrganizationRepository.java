package uz.platform.organizationservice.repo;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import uz.platform.organizationservice.domain.UserOrganization;

public interface UserOrganizationRepository extends JpaRepository<UserOrganization, UUID> {

    List<UserOrganization> findByKeycloakSubAndActiveTrue(UUID keycloakSub);

    boolean existsByKeycloakSubAndOrganization_TinAndActiveTrue(UUID keycloakSub, String tin);
}
