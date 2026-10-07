package com.geekonsites.backend.repository;

import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.entity.CrmFollowUp;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.ServiceMode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import com.geekonsites.backend.enums.Role;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);
    Optional<User> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
    long countByRole(Role role);

    // PHASE 9 — operational, DB-side, paginated customer list with search + filters.
    @Query("""
        select u from User u
        where u.role = com.geekonsites.backend.enums.Role.CUSTOMER
          and (:q = '' or lower(u.fullName) like concat('%', :q, '%')
                        or lower(u.email) like concat('%', :q, '%')
                        or lower(u.phone) like concat('%', :q, '%'))
          and (:country = '' or upper(u.country) = upper(:country))
          and (:bookingStatus = '' or exists (
                select 1 from Booking b
                where b.customerId = u.id and b.bookingStatus = :bookingStatusValue
                  and b.createdAt = (select max(b2.createdAt) from Booking b2 where b2.customerId = u.id)))
          and (:serviceMode = '' or exists (
                select 1 from Booking b
                where b.customerId = u.id and b.serviceMode = :serviceModeValue
                  and b.createdAt = (select max(b2.createdAt) from Booking b2 where b2.customerId = u.id)))
          and (:followUpStatus = '' or
                (:followUpStatus = 'NONE' and not exists (
                    select 1 from CrmFollowUp f where f.customerId = u.id and f.status = :pendingStatus))
                or (:followUpStatus <> 'NONE' and exists (
                    select 1 from CrmFollowUp f
                    where f.customerId = u.id and f.status = :pendingStatus
                      and f.followUpAt = (select min(f2.followUpAt) from CrmFollowUp f2
                                           where f2.customerId = u.id and f2.status = :pendingStatus)
                      and ((:followUpStatus = 'OVERDUE' and f.followUpAt < :todayStart)
                           or (:followUpStatus = 'DUE_TODAY' and f.followUpAt >= :todayStart and f.followUpAt < :todayEnd)
                           or (:followUpStatus = 'UPCOMING' and f.followUpAt >= :todayEnd)))))
        """)
    Page<User> fetchCrmCustomers(
            @Param("q") String q,
            @Param("country") String country,
            @Param("bookingStatus") String bookingStatus,
            @Param("bookingStatusValue") BookingStatus bookingStatusValue,
            @Param("serviceMode") String serviceMode,
            @Param("serviceModeValue") ServiceMode serviceModeValue,
            @Param("followUpStatus") String followUpStatus,
            @Param("pendingStatus") CrmFollowUp.Status pendingStatus,
            @Param("todayStart") LocalDateTime todayStart,
            @Param("todayEnd") LocalDateTime todayEnd,
            Pageable pageable);

    @Query("select u from User u where u.role = com.geekonsites.backend.enums.Role.CUSTOMER and lower(u.email) in :emails")
    List<User> findCustomersByEmails(@Param("emails") Collection<String> emails);

    // PHASE 9 — paginated admin user/customer list with DB-side search.
    @Query("""
        select u from User u
        where u.role = :role
          and (:q = '' or lower(u.fullName) like concat('%', :q, '%')
                        or lower(u.email) like concat('%', :q, '%'))
        """)
    Page<User> searchByRole(@Param("role") Role role, @Param("q") String q, Pageable pageable);
}
