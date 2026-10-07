package com.geekonsites.backend.repository;

import com.geekonsites.backend.entity.ContactMessage;
import com.geekonsites.backend.repository.projection.CustomerActivity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.time.LocalDateTime;

@Repository
public interface ContactRepository extends JpaRepository<ContactMessage, Long> {

    List<ContactMessage> findAllByOrderByCreatedAtDesc();

    // PHASE 9 — bounded operational feed.
    Page<ContactMessage> findAllByOrderByCreatedAtDesc(Pageable pageable);

    // PHASE 9 — contacts for one customer (id match OR email match), no full-table load.
    @Query("""
        select c from ContactMessage c
        where c.customerId = :customerId or lower(c.email) = lower(:email)
        order by c.createdAt desc
        """)
    List<ContactMessage> findForCustomer(@Param("customerId") Long customerId, @Param("email") String email);

    // PHASE 9 — grouped "latest contact" aggregate for a page of customers (no N+1).
    @Query("""
        select new com.geekonsites.backend.repository.projection.CustomerActivity(c.customerId, max(c.createdAt))
        from ContactMessage c where c.customerId in :ids group by c.customerId
        """)
    List<CustomerActivity> lastContactByCustomer(@Param("ids") Collection<Long> ids);

    @Query("select count(c) from ContactMessage c where upper(c.status) not in :statuses")
    long countOpen(@Param("statuses") Collection<String> statuses);

    long countByStatusIgnoreCase(String status);
    long countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(LocalDateTime start, LocalDateTime end);

}
