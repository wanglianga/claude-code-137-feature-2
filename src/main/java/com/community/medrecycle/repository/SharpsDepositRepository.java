package com.community.medrecycle.repository;

import com.community.medrecycle.domain.SharpsDeposit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SharpsDepositRepository extends JpaRepository<SharpsDeposit, Long> {

    Optional<SharpsDeposit> findByDepositNo(String depositNo);

    List<SharpsDeposit> findByContainerIdOrderByConfirmedAtAsc(Long containerId);

    List<SharpsDeposit> findByBagId(Long bagId);

    List<SharpsDeposit> findByBatchId(Long batchId);
}
