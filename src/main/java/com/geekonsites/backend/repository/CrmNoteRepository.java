package com.geekonsites.backend.repository;
import com.geekonsites.backend.entity.CrmNote;
import com.geekonsites.backend.repository.projection.CustomerActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
public interface CrmNoteRepository extends JpaRepository<CrmNote, Long> {
    List<CrmNote> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    // PHASE 9 — grouped "latest note" aggregate for a page of customers (no N+1).
    @Query("""
        select new com.geekonsites.backend.repository.projection.CustomerActivity(n.customerId, max(n.createdAt))
        from CrmNote n where n.customerId in :ids group by n.customerId
        """)
    List<CustomerActivity> lastActivityByCustomer(@Param("ids") Collection<Long> ids);
}
