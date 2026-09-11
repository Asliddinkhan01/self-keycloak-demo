package uz.platform.cadastralservice.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A payment recorded against a project. Created by the BANK role. */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "organization_tin", nullable = false, length = 9)
    private String organizationTin;

    @Column(name = "created_by_sub", nullable = false, updatable = false)
    private UUID createdBySub;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Payment() {
        // required by JPA
    }

    public Payment(UUID projectId, BigDecimal amount, String organizationTin, UUID createdBySub) {
        this.projectId = projectId;
        this.amount = amount;
        this.organizationTin = organizationTin;
        this.createdBySub = createdBySub;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public BigDecimal getAmount() {
        return amount;
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
}
