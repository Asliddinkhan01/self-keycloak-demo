package com.example.userservice.domain;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A local profile row in user_service.app_user.
 *
 * <p>Notice what this entity does NOT have: no password, no password hash, no
 * roles. Keycloak owns identity and authorization; this service owns only the
 * application data that Keycloak has no business storing. The two are joined by
 * {@link #keycloakId}, which holds the token's immutable "sub" claim.</p>
 *
 * <p>The schema is not named here. It comes from
 * {@code spring.jpa.properties.hibernate.default_schema} in application.yml, so
 * the mapping stays free of environment detail.</p>
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    // IDENTITY matches the BIGSERIAL column created by the Flyway migration.
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "keycloak_id", length = 36, unique = true)
    private String keycloakId;

    @Column(nullable = false, length = 60, unique = true)
    private String username;

    @Column(name = "full_name", length = 120)
    private String fullName;

    @Column(length = 160)
    private String email;

    // Hibernate fills this in on insert. The DEFAULT now() in the migration
    // stays as a safety net for rows inserted by plain SQL, such as the seeds.
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected AppUser() {
        // required by JPA
    }

    public AppUser(String keycloakId, String username, String fullName, String email) {
        this.keycloakId = keycloakId;
        this.username = username;
        this.fullName = fullName;
        this.email = email;
    }

    public Long getId() {
        return id;
    }

    public String getKeycloakId() {
        return keycloakId;
    }

    public String getUsername() {
        return username;
    }

    public String getFullName() {
        return fullName;
    }

    public String getEmail() {
        return email;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    /** Called the first time a seeded row is matched to a real Keycloak account. */
    public void linkToKeycloak(String keycloakId) {
        this.keycloakId = keycloakId;
    }

    /** Keeps the local profile in step with whatever Keycloak currently says. */
    public void updateProfile(String fullName, String email) {
        this.fullName = fullName;
        this.email = email;
    }
}
