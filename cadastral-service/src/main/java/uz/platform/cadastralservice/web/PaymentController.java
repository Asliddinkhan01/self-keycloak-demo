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
import uz.platform.cadastralservice.repo.ProjectRepository;
import uz.platform.security.OrganizationContext;
import uz.platform.security.PlatformClaims;

/**
 * Payments, held by the BANK role and scoped to the acting organization.
 *
 * <p>These endpoints prove that effective permissions are the <b>union across all
 * of a caller's roles</b>, with no precedence and no role containing another:</p>
 *
 * <ul>
 *   <li>{@code ali} is QURUVCHI: creates projects, refused here.</li>
 *   <li>{@code bank_user} is BANK: creates payments, refused on projects.</li>
 *   <li>{@code dual} is both: creates projects <em>and</em> payments, with no
 *       special case anywhere in the code.</li>
 * </ul>
 *
 * <p>Permission and organization remain independent. Holding
 * {@code PAYMENT_CREATE} says a caller may record payments; the verified TIN says
 * which company's books they are recording them in.</p>
 *
 * <p>A payment must reference a project of that same organization. Checking the
 * caller's membership is not enough on its own: the project id arrives in the
 * request body, so without this check a BANK operator acting for their own
 * company could record a payment against any other company's project, just by
 * knowing its id. Found while documenting phase 13, by calling the API as an
 * attacker would.</p>
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentRepository payments;
    private final ProjectRepository projects;
    private final OrganizationContext organizationContext;

    public PaymentController(PaymentRepository payments, ProjectRepository projects,
                             OrganizationContext organizationContext) {
        this.payments = payments;
        this.projects = projects;
        this.organizationContext = organizationContext;
    }

    @PreAuthorize("@permissionChecker.has(authentication, 'PAYMENT_READ')")
    @GetMapping
    public List<Map<String, Object>> list() {
        String tin = organizationContext.requireTin();
        return payments.findByOrganizationTinOrderByCreatedAtDesc(tin).stream()
                .map(PaymentController::toView)
                .toList();
    }

    @PreAuthorize("@permissionChecker.has(authentication, 'PAYMENT_CREATE')")
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreatePaymentRequest request,
                                                      @AuthenticationPrincipal Jwt jwt) {
        String tin = organizationContext.requireTin();

        // 404, not 403: another organization's project is simply not found from
        // here, exactly as ProjectController.update answers. A 403 would confirm
        // that the id exists.
        boolean projectOfThisOrganization = projects.findById(request.projectId())
                .filter(project -> project.getOrganizationTin().equals(tin))
                .isPresent();
        if (!projectOfThisOrganization) {
            return ResponseEntity.notFound().build();
        }

        Payment created = payments.save(new Payment(
                request.projectId(),
                request.amount(),
                tin,
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
