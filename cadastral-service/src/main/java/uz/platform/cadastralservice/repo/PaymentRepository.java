package uz.platform.cadastralservice.repo;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import uz.platform.cadastralservice.domain.Payment;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findAllByOrderByCreatedAtDesc();
}
