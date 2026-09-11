package uz.platform.userservice.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A profile row in {@code user_service.users}.
 *
 * <p>Notice what this entity does <b>not</b> have: no password, no password hash,
 * no roles, no PIN, no passport number. Keycloak owns identity and authorization;
 * this service owns only the application data Keycloak has no business storing.
 * The two are joined by {@link #keycloakSub}, the token's {@code sub} claim.</p>
 *
 * <p>The schema name is not written here. It comes from
 * {@code spring.jpa.properties.hibernate.default_schema}, so the mapping stays
 * free of environment detail and the same code can run against a differently
 * named schema elsewhere.</p>
 */
@Entity
@Table(name = "users")
public class AppUser {

    // Client-side UUID generation. The DEFAULT gen_random_uuid() in the
    // migration stays as a safety net for rows inserted by plain SQL.
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "keycloak_sub", nullable = false, unique = true, updatable = false)
    private UUID keycloakSub;

    @Column(name = "oneid_user_id", length = 255)
    private String oneidUserId;

    @Column(name = "full_name", length = 255)
    private String fullName;

    @Column(name = "first_name", length = 255)
    private String firstName;

    @Column(name = "sur_name", length = 255)
    private String surName;

    @Column(name = "mid_name", length = 255)
    private String midName;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    /** OneID {@code user_type}: {@code I} physical person, {@code L} legal entity. */
    @Column(name = "user_type", length = 1)
    private String userType;

    /** From OneID's {@code valid} field. An assurance level, not a login check. */
    @Column(name = "identity_verified", nullable = false)
    private boolean identityVerified;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    protected AppUser() {
        // required by JPA
    }

    public AppUser(UUID keycloakSub, String oneidUserId) {
        this.keycloakSub = keycloakSub;
        this.oneidUserId = oneidUserId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getKeycloakSub() {
        return keycloakSub;
    }

    public String getOneidUserId() {
        return oneidUserId;
    }

    public String getFullName() {
        return fullName;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getSurName() {
        return surName;
    }

    public String getMidName() {
        return midName;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public String getUserType() {
        return userType;
    }

    public boolean isIdentityVerified() {
        return identityVerified;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    /** Administrative edit of the display name. */
    public void rename(String fullName) {
        this.fullName = fullName;
    }

    /**
     * Refreshes the profile from the verified token and stamps the login.
     *
     * <p>OneID is authoritative for these fields and this row is a cache, so they
     * are overwritten on every login. Roles are deliberately not touched here:
     * those are the platform's, and overwriting them would silently revoke
     * privileges an administrator granted.</p>
     */
    public void refreshFrom(String oneidUserId, String fullName, String firstName, String surName,
                            String userType, boolean identityVerified) {
        this.oneidUserId = oneidUserId;
        this.fullName = fullName;
        this.firstName = firstName;
        this.surName = surName;
        this.userType = userType;
        this.identityVerified = identityVerified;
        this.lastLoginAt = OffsetDateTime.now();
    }
}
