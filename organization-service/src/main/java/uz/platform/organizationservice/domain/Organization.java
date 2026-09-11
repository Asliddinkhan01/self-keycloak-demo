package uz.platform.organizationservice.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A legal entity, identified by its STIR (taxpayer number). */
@Entity
@Table(name = "organizations")
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** STIR. Nine digits, and the identifier OneID uses in legal_info[].tin. */
    @Column(nullable = false, unique = true, length = 9)
    private String tin;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "short_name", length = 120)
    private String shortName;

    @Column(nullable = false)
    private boolean active;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Organization() {
        // required by JPA
    }

    public Organization(String tin, String name, String shortName) {
        this.tin = tin;
        this.name = name;
        this.shortName = shortName;
        this.active = true;
    }

    public UUID getId() {
        return id;
    }

    public String getTin() {
        return tin;
    }

    public String getName() {
        return name;
    }

    public String getShortName() {
        return shortName;
    }

    public boolean isActive() {
        return active;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
