package uz.platform.cadastralservice.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Request body of POST /api/payments.
 *
 * <p>As with projects, the organization is not part of the body. It comes from
 * the verified X-Organization-TIN header.
 */
public record CreatePaymentRequest(
        @NotNull UUID projectId,
        @NotNull @Positive BigDecimal amount) {
}
