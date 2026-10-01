package com.community.medrecycle.repository;

import com.community.medrecycle.domain.SharpsContainer;
import com.community.medrecycle.domain.enums.SharpsStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SharpsContainerRepository extends JpaRepository<SharpsContainer, Long> {

    List<SharpsContainer> findBySiteIdOrderByCreatedAtDesc(Long siteId);

    List<SharpsContainer> findBySiteIdAndStatus(Long siteId, SharpsStatus status);

    List<SharpsContainer> findBySiteIdAndStatusAndPackagingDamagedFalse(Long siteId, SharpsStatus status);

    Optional<SharpsContainer> findByContainerNo(String containerNo);

    Optional<SharpsContainer> findBySpecialLabelNo(String specialLabelNo);

    /** 转入并发控制：悲观锁防止锐器盒容量超卖 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from SharpsContainer c where c.id = :id")
    Optional<SharpsContainer> lockById(@Param("id") Long id);
}
