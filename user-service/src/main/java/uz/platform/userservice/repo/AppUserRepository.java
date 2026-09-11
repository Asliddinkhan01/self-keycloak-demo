package uz.platform.userservice.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import uz.platform.userservice.domain.AppUser;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByKeycloakSub(UUID keycloakSub);

    List<AppUser> findAllByOrderByOneidUserIdAsc();
}
