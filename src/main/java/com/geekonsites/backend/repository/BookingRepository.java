package com.geekonsites.backend.repository;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.enums.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.geekonsites.backend.repository.projection.AgentBookingQueueView;
import com.geekonsites.backend.repository.projection.BookingStatusCountView;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") Long id);

    List<Booking> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    List<Booking> findByTechnicianIdOrderByCreatedAtDesc(Long technicianId);

    List<Booking> findByAgentIdOrderByCreatedAtDesc(Long agentId);

    List<Booking> findByTechnicianIdIsNullOrderByCreatedAtDesc();

    List<Booking> findByBookingStatusOrderByCreatedAtDesc(BookingStatus bookingStatus);

    List<Booking> findByTechnicianIdAndBookingStatusOrderByCreatedAtDesc(
            Long technicianId,
            BookingStatus bookingStatus
    );

    List<Booking> findByCustomerIdAndBookingStatusOrderByCreatedAtDesc(
            Long customerId,
            BookingStatus bookingStatus
    );

    Page<AgentBookingQueueView> findAllProjectedBy(Pageable pageable);

    @Query("""
        select new com.geekonsites.backend.dto.AgentOperationsDtos$BookingCounts(
          count(b),
          coalesce(sum(case when b.bookingStatus in :pending then 1 else 0 end), 0),
          coalesce(sum(case when b.bookingStatus in :assigned then 1 else 0 end), 0),
          coalesce(sum(case when b.bookingStatus = com.geekonsites.backend.enums.BookingStatus.SERVICE_COMPLETED then 1 else 0 end), 0),
          coalesce(sum(case when b.serviceMode = com.geekonsites.backend.enums.ServiceMode.REMOTE or b.remoteSessionRequired = true then 1 else 0 end), 0)
        ) from Booking b
        """)
    com.geekonsites.backend.dto.AgentOperationsDtos.BookingCounts countOperations(
            @Param("pending") List<BookingStatus> pending,
            @Param("assigned") List<BookingStatus> assigned);

    @Query("""
        select new com.geekonsites.backend.dto.AgentOperationsDtos$PeriodCounts(
          coalesce(sum(case when b.createdAt >= :start and b.createdAt < :end then 1 else 0 end), 0),
          coalesce(sum(case when b.createdAt >= :start and b.createdAt < :end and b.bookingStatus in :pending then 1 else 0 end), 0),
          coalesce(sum(case when b.createdAt >= :start and b.createdAt < :end and b.bookingStatus in :assigned then 1 else 0 end), 0),
          coalesce(sum(case when b.serviceCompletedAt >= :start and b.serviceCompletedAt < :end then 1 else 0 end), 0),
          coalesce(sum(case when b.createdAt >= :start and b.createdAt < :end and b.serviceMode = com.geekonsites.backend.enums.ServiceMode.ONSITE then 1 else 0 end), 0),
          coalesce(sum(case when b.createdAt >= :start and b.createdAt < :end and (b.serviceMode = com.geekonsites.backend.enums.ServiceMode.REMOTE or b.remoteSessionRequired = true) then 1 else 0 end), 0),
          0
        ) from Booking b
        """)
    com.geekonsites.backend.dto.AgentOperationsDtos.PeriodCounts countPeriod(
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end,
            @Param("pending") List<BookingStatus> pending,
            @Param("assigned") List<BookingStatus> assigned);

    @Query("select b.bookingStatus as status, count(b) as total from Booking b group by b.bookingStatus")
    List<BookingStatusCountView> countByBookingStatus();

    @Query("select count(distinct b.agentId) from Booking b where b.agentId is not null and b.bookingStatus not in :closed")
    long countDistinctAgentsWithActiveBookings(@Param("closed") List<BookingStatus> closed);

    long countByServiceMode(com.geekonsites.backend.enums.ServiceMode mode);
    long countByServiceModeAndBookingStatusIn(com.geekonsites.backend.enums.ServiceMode mode, List<BookingStatus> statuses);
    long countByServiceModeAndBookingStatus(com.geekonsites.backend.enums.ServiceMode mode, BookingStatus status);

    @Query("""
        select count(b) from Booking b
        where b.agentId = :agentId and b.bookingStatus not in :closed
        """)
    long countActiveAssignedToAgent(@Param("agentId") Long agentId, @Param("closed") List<BookingStatus> closed);

    long countByAgentIdAndBookingStatusIn(Long agentId, List<BookingStatus> statuses);
}
