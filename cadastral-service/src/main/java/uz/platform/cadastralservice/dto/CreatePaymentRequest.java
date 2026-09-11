package uz.platform.cadastralservice.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Request body of POST /api/payments. */
public record CreatePaymentRequest(
        @NotNull UUID projectId,
        @NotNull @Positive BigDecimal amount,
        @NotNull @Size(min = 9, max = 9) String organizationTin) {
}
