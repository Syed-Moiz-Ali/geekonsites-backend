package com.geekonsites.backend.repository;
import com.geekonsites.backend.entity.CrmNote;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface CrmNoteRepository extends JpaRepository<CrmNote, Long> {
    List<CrmNote> findByCustomerIdOrderByCreatedAtDesc(Long customerId);
}
