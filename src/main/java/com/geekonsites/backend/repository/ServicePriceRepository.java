package com.geekonsites.backend.repository;

import com.geekonsites.backend.entity.ServicePrice;
import com.geekonsites.backend.enums.Currency;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ServicePriceRepository extends JpaRepository<ServicePrice, Long> {

    Optional<ServicePrice> findByServiceIdAndCurrency(Long serviceId, Currency currency);

    List<ServicePrice> findByServiceId(Long serviceId);

    List<ServicePrice> findByServiceIdIn(Collection<Long> serviceIds);
}
