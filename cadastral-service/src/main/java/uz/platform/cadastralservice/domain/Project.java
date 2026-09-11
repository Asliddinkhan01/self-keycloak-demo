package uz.platform.cadastralservice.domain;

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
 * A construction project, always owned by an organization.
 *
 * <p>{@link #organizationTin} is a value, not a foreign key: this service has no
 * privileges on the organization_service schema, so a join is impossible by
 * design. The authoritative organization record lives one HTTP call away.</p>
 *
 * <p>{@link #createdBySub} comes from the verified token, never from a request
 * body or a header. A caller cannot claim to be someone else, because the value
 * is read from a signature Keycloak produced.</p>
 */
@Entity
@Table(name = "projects")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 255)
    private String address;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "organization_tin", nullable = false, length = 9)
    private String organizationTin;

    @Column(name = "created_by_sub", nullable = false, updatable = false)
    private UUID createdBySub;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Project() {
        // required by JPA
    }

    public Project(String name, String address, String organizationTin, UUID createdBySub) {
        this.name = name;
        this.address = address;
        this.status = "DRAFT";
        this.organizationTin = organizationTin;
        this.createdBySub = createdBySub;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public String getStatus() {
        return status;
    }

    public String getOrganizationTin() {
        return organizationTin;
    }

    public UUID getCreatedBySub() {
        return createdBySub;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void rename(String name, String address) {
        this.name = name;
        this.address = address;
    }
}
