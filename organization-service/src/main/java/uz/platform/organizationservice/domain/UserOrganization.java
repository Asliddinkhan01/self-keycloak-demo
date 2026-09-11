package uz.platform.organizationservice.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One person's membership of one organization.
 *
 * <p>This table is the source of truth for "may this person act on behalf of
 * this organization". The {@code org_tins} claim in the token seeds it at login;
 * it never makes the decision. That separation is what lets an administrator
 * grant a membership OneID knows nothing about, and lets one be revoked without
 * waiting for a token to expire.</p>
 *
 * <p>{@link #keycloakSub} is a plain UUID, not a foreign key: this service has
 * no privileges on the user_service schema, and in a microservice system it
 * should not want them.</p>
 */
@Entity
@Table(name = "user_organizations")
public class UserOrganization {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "keycloak_sub", nullable = false, updatable = false)
    private UUID keycloakSub;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    /** From OneID legal_info[].is_basic: the entity the person selected there. */
    @Column(name = "is_basic", nullable = false)
    private boolean basic;

    /** ONEID when reconciled from a token, MANUAL when an administrator granted it. */
    @Column(nullable = false, length = 16)
    private String source;

    /** Deactivated rather than deleted, so historical records still resolve. */
    @Column(nullable = false)
    private boolean active;

    @CreationTimestamp
    @Column(name = "linked_at", nullable = false, updatable = false)
    private OffsetDateTime linkedAt;

    protected UserOrganization() {
        // required by JPA
    }

    public UserOrganization(UUID keycloakSub, Organization organization, boolean basic, String source) {
        this.keycloakSub = keycloakSub;
        this.organization = organization;
        this.basic = basic;
        this.source = source;
        this.active = true;
    }

    public UUID getId() {
        return id;
    }

    public UUID getKeycloakSub() {
        return keycloakSub;
    }

    public Organization getOrganization() {
        return organization;
    }

    public boolean isBasic() {
        return basic;
    }

    public String getSource() {
        return source;
    }

    public boolean isActive() {
        return active;
    }

    public OffsetDateTime getLinkedAt() {
        return linkedAt;
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }
}
