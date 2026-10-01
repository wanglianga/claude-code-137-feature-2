package com.community.medrecycle.repository;

import com.community.medrecycle.domain.SharpContainer;
import com.community.medrecycle.domain.enums.SharpContainerStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SharpContainerRepository extends JpaRepository<SharpContainer, Long> {
    Optional<SharpContainer> findByContainerNo(String containerNo);
    List<SharpContainer> findBySiteIdOrderByIdAsc(Long siteId);
    List<SharpContainer> findBySiteIdAndStatus(Long siteId, SharpContainerStatus status);

    /** 投放/封箱并发控制：悲观锁防止锐器盒容量超装 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from SharpContainer c where c.id = :id")
    Optional<SharpContainer> lockById(@Param("id") Long id);
}
