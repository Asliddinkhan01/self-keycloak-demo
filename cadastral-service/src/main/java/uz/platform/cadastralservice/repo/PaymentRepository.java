package uz.platform.cadastralservice.repo;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import uz.platform.cadastralservice.domain.Payment;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /**
     * Scoped by organization, because that is the authorization boundary. There
     * is deliberately no findAll-style method on the request path: it would be
     * one careless call away from leaking another company's records.
     */
    List<Payment> findByOrganizationTinOrderByCreatedAtDesc(String organizationTin);
}
