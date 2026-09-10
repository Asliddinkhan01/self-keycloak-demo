package com.example.userservice.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.userservice.domain.AppUser;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByKeycloakId(String keycloakId);

    Optional<AppUser> findByUsername(String username);

    List<AppUser> findAllByOrderByUsernameAsc();
}
