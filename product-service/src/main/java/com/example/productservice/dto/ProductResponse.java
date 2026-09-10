package com.example.productservice.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.example.productservice.domain.Product;

/** What the API returns, kept separate from the JPA entity. */
public record ProductResponse(
        Long id,
        String name,
        BigDecimal price,
        String createdBy,
        OffsetDateTime createdAt) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getPrice(),
                product.getCreatedBy(),
                product.getCreatedAt());
    }
}
