package com.geekonsites.backend.repository;
import com.geekonsites.backend.entity.CrmFollowUp;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.time.LocalDateTime;
public interface CrmFollowUpRepository extends JpaRepository<CrmFollowUp, Long> {
    List<CrmFollowUp> findByCustomerIdOrderByFollowUpAtAsc(Long customerId);
    long countByStatusAndFollowUpAtLessThanEqual(CrmFollowUp.Status status, LocalDateTime end);
    long countByStatus(CrmFollowUp.Status status);
}
