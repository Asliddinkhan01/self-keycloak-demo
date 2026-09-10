package com.example.userservice.service;

import java.util.List;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.userservice.domain.AppUser;
import com.example.userservice.repo.AppUserRepository;

/**
 * Keeps the local profile table in step with Keycloak.
 *
 * <p>This is the pattern most real systems use, usually called just-in-time
 * provisioning. Keycloak is the source of truth for identity; the service keeps
 * its own row so it can attach application data (orders, preferences, an avatar)
 * to a user and join on it in SQL. The row is created the first time that user
 * calls an endpoint, never in advance, and it is keyed on the token's "sub"
 * claim rather than the username, because a username can be changed by an
 * administrator while "sub" never changes.</p>
 */
@Service
public class UserProfileService {

    private final AppUserRepository repository;

    public UserProfileService(AppUserRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<AppUser> findAll() {
        return repository.findAllByOrderByUsernameAsc();
    }

    /**
     * Returns the caller's profile row, creating or linking it if needed.
     *
     * <p>Three cases, in order:</p>
     * <ol>
     *   <li>a row already carries this "sub": return it, refreshing name and e-mail;</li>
     *   <li>a seeded row has the same username but no "sub" yet: link it;</li>
     *   <li>nobody matches, for example a user who just signed up: insert a new row.</li>
     * </ol>
     */
    @Transactional
    public AppUser findOrCreateFrom(Jwt jwt) {
        String subject = jwt.getSubject();
        String username = jwt.getClaimAsString("preferred_username");
        String fullName = jwt.getClaimAsString("name");
        String email = jwt.getClaimAsString("email");

        return repository.findByKeycloakId(subject)
                .map(existing -> {
                    existing.updateProfile(fullName, email);
                    return existing;
                })
                .orElseGet(() -> repository.findByUsername(username)
                        .map(seeded -> {
                            seeded.linkToKeycloak(subject);
                            seeded.updateProfile(fullName, email);
                            return seeded;
                        })
                        .orElseGet(() -> repository.save(
                                new AppUser(subject, username, fullName, email))));
    }
}
