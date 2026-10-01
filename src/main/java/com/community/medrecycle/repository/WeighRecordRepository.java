package com.community.medrecycle.repository;

import com.community.medrecycle.domain.WeighRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WeighRecordRepository extends JpaRepository<WeighRecord, Long> {
    List<WeighRecord> findByBatchIdOrderByCreatedAtAsc(Long batchId);
    Optional<WeighRecord> findByBagId(Long bagId);
}
