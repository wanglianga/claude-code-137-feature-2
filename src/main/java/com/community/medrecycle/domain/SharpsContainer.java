package com.community.medrecycle.domain;

import com.community.medrecycle.domain.enums.SharpsStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 锐器盒（防刺安全容器）：胰岛素针头、注射器等针剂不得进入普通暂存柜，
 * 只能转入锐器盒暂存；封盒后生成特殊交接标签整体交接给环保公司。
 */
@Getter
@Setter
@Entity
@Table(name = "sharps_container", uniqueConstraints = {
        @UniqueConstraint(columnNames = "containerNo"),
        @UniqueConstraint(columnNames = "specialLabelNo")
}, indexes = @Index(name = "idx_sharps_site_status", columnList = "siteId,status"))
public class SharpsContainer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String containerNo;

    @Column(nullable = false)
    private Long siteId;

    @Column(nullable = false, length = 64)
    private String name;

    /** 容量上限：锐器（针头/注射器）数量 */
    @Column(nullable = false)
    private int capacitySharps;

    /** 容量上限：重量 kg */
    @Column(nullable = false)
    private double capacityWeightKg;

    /** 预警比例，达到后提前通知环保公司/上级回收点 */
    @Column(nullable = false)
    private double warnRatio = 0.8;

    @Column(nullable = false)
    private int usedSharps = 0;

    @Column(nullable = false)
    private double usedWeightKg = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SharpsStatus status = SharpsStatus.IN_USE;

    /** 签收时发现包装破损、等待回收点重新封装期间禁止再次交接 */
    @Column(nullable = false)
    private boolean packagingDamaged = false;

    /** 已分配到的在途交接单，防止封盒锐器盒被重复建单 */
    private Long assignedOrderId;

    /** 特殊交接标签编号（SHL），封盒时生成；破损重新封装后新盒换新标签 */
    @Column(length = 32)
    private String specialLabelNo;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime sealedAt;

    private LocalDateTime handedOverAt;

    private LocalDateTime disposedAt;

    @Version
    private long version;
}
