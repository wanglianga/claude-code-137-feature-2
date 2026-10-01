package com.community.medrecycle.repository;

import com.community.medrecycle.domain.SharpDeposit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SharpDepositRepository extends JpaRepository<SharpDeposit, Long> {
    Optional<SharpDeposit> findByDepositNo(String depositNo);
    Optional<SharpDeposit> findByBagId(Long bagId);
    List<SharpDeposit> findByContainerIdOrderByCreatedAtAsc(Long containerId);
    List<SharpDeposit> findByBatchId(Long batchId);
}
