package com.geekonsites.backend.repository;

import com.geekonsites.backend.entity.Agent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentRepository extends JpaRepository<Agent, Long> {

    Optional<Agent> findByEmail(String email);
    long countByStatusIgnoreCase(String status);
}
