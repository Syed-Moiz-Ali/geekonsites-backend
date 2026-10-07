package com.geekonsites.backend.repository;

import com.geekonsites.backend.entity.Service;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ServiceRepository extends JpaRepository<Service, Long> {

    Optional<Service> findByCodeIgnoreCase(String code);

    Optional<Service> findByNameIgnoreCase(String name);

    List<Service> findByActiveTrueOrderBySortOrderAscNameAsc();

    List<Service> findAllByOrderBySortOrderAscNameAsc();

    boolean existsByCodeIgnoreCase(String code);
}
