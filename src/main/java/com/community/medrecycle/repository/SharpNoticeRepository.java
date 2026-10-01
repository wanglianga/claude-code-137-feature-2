package com.community.medrecycle.repository;

import com.community.medrecycle.domain.SharpNotice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SharpNoticeRepository extends JpaRepository<SharpNotice, Long> {
    Optional<SharpNotice> findByNoticeNo(String noticeNo);
    List<SharpNotice> findBySiteIdOrderByCreatedAtDesc(Long siteId);
    List<SharpNotice> findByContainerIdOrderByCreatedAtAsc(Long containerId);
}
