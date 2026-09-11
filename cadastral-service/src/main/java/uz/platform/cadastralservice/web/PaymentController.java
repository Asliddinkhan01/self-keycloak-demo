package uz.platform.cadastralservice.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import uz.platform.cadastralservice.domain.Payment;
import uz.platform.cadastralservice.dto.CreatePaymentRequest;
import uz.platform.cadastralservice.repo.PaymentRepository;
import uz.platform.security.PlatformClaims;

/**
 * Payments, held by the BANK role.
 *
 * <p>These two endpoints exist to prove that effective permissions are the
 * <b>union across all of a caller's roles</b>, with no precedence and no role
 * containing another:</p>
 *
 * <ul>
 *   <li>{@code ali} is QURUVCHI: creates projects, refused here.</li>
 *   <li>{@code bank_user} is BANK: creates payments, refused on projects.</li>
 *   <li>{@code dual} is both: creates projects <em>and</em> payments, with no
 *       special case anywhere in the code.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentRepository payments;

    public PaymentController(PaymentRepository payments) {
        this.payments = payments;
    }

    @PreAuthorize("@permissionChecker.has(authentication, 'PAYMENT_READ')")
    @GetMapping
    public List<Map<String, Object>> list() {
        return payments.findAllByOrderByCreatedAtDesc().stream().map(PaymentController::toView).toList();
    }

    @PreAuthorize("@permissionChecker.has(authentication, 'PAYMENT_CREATE')")
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreatePaymentRequest request,
                                                      @AuthenticationPrincipal Jwt jwt) {
        Payment created = payments.save(new Payment(
                request.projectId(),
                request.amount(),
                request.organizationTin(),
                PlatformClaims.subject(jwt)));
        return ResponseEntity.status(HttpStatus.CREATED).body(toView(created));
    }

    private static Map<String, Object> toView(Payment payment) {
        return Map.of(
                "id", payment.getId(),
                "projectId", payment.getProjectId(),
                "amount", payment.getAmount(),
                "organizationTin", payment.getOrganizationTin(),
                "createdBySub", payment.getCreatedBySub());
    }
}
