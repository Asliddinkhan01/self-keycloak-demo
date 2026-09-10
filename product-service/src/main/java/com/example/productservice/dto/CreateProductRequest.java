package com.example.productservice.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Request body of POST /api/products. */
public record CreateProductRequest(
        @NotBlank String name,
        @NotNull @Positive BigDecimal price) {
}
