package com.community.medrecycle.repository;

import com.community.medrecycle.domain.SharpsNotification;
import com.community.medrecycle.domain.enums.SharpsNotifyType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SharpsNotificationRepository extends JpaRepository<SharpsNotification, Long> {

    List<SharpsNotification> findBySiteIdOrderByCreatedAtDesc(Long siteId);

    boolean existsByContainerIdAndType(Long containerId, SharpsNotifyType type);
}
