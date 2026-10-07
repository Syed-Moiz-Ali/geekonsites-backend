package com.geekonsites.backend.repository;
import com.geekonsites.backend.entity.CrmFollowUp;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.time.LocalDateTime;
public interface CrmFollowUpRepository extends JpaRepository<CrmFollowUp, Long> {
    List<CrmFollowUp> findByCustomerIdOrderByFollowUpAtAsc(Long customerId);

    // PHASE 9 — one batched query for a page of CRM customers (no N+1).
    List<CrmFollowUp> findByCustomerIdIn(Collection<Long> customerIds);

    long countByStatusAndFollowUpAtLessThanEqual(CrmFollowUp.Status status, LocalDateTime end);
    long countByStatusAndFollowUpAtLessThan(CrmFollowUp.Status status, LocalDateTime at);
    long countByStatus(CrmFollowUp.Status status);
}
