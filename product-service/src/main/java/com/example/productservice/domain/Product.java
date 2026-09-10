package com.example.productservice.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A row in product_service.product.
 *
 * <p>The schema name is not written here; it comes from
 * {@code spring.jpa.properties.hibernate.default_schema} in application.yml.</p>
 */
@Entity
@Table(name = "product")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    // precision/scale mirror NUMERIC(10,2) in the migration, so that
    // ddl-auto: validate can confirm the entity and the table agree.
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    /**
     * The preferred_username claim of whoever created the row. A plain string,
     * not a foreign key: this service has no privileges on user_service.app_user
     * and in a microservice system it should not want them.
     */
    @Column(name = "created_by", nullable = false, length = 60)
    private String createdBy;

    // Hibernate fills this in on insert, so the value is available immediately
    // after save() without a second round trip. The DEFAULT now() in the
    // migration stays as a safety net for rows inserted by plain SQL.
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Product() {
        // required by JPA
    }

    public Product(String name, BigDecimal price, String createdBy) {
        this.name = name;
        this.price = price;
        this.createdBy = createdBy;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
