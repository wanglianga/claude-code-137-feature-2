package com.community.medrecycle.repository;

import com.community.medrecycle.domain.HandoverContainer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HandoverContainerRepository extends JpaRepository<HandoverContainer, Long> {

    List<HandoverContainer> findByOrderId(Long orderId);
}
