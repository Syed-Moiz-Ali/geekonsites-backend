package com.geekonsites.backend.repository;

import com.geekonsites.backend.entity.ServiceAddon;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ServiceAddonRepository extends JpaRepository<ServiceAddon, Long> {

    Optional<ServiceAddon> findByCode(String code);

    boolean existsByCode(String code);

    List<ServiceAddon> findByActiveTrue();

    List<ServiceAddon> findAllByOrderBySortOrderAscCodeAsc();
}
