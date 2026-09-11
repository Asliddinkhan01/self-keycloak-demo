package uz.platform.organizationservice.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import uz.platform.organizationservice.domain.Organization;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    Optional<Organization> findByTin(String tin);

    List<Organization> findAllByOrderByTinAsc();
}
