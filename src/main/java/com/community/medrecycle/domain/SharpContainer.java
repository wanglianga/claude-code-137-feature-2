package com.community.medrecycle.domain;

import com.community.medrecycle.domain.enums.SharpContainerStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 锐器盒（防刺容器）：针剂/注射器只能投入锐器盒，不能进入普通暂存柜。
 * 达容量上限停止接收并提前通知环保公司或上级回收点；封箱后生成特殊交接标签随交接单移交。
 */
@Getter
@Setter
@Entity
@Table(name = "sharp_container", uniqueConstraints = {
        @UniqueConstraint(columnNames = "containerNo"),
        @UniqueConstraint(columnNames = "handoffLabelNo")
}, indexes = {
        @Index(name = "idx_sharp_site_status", columnList = "siteId,status")
})
public class SharpContainer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String containerNo;

    @Column(nullable = false)
    private Long siteId;

    @Column(nullable = false, length = 64)
    private String name;

    /** 容量上限：锐器数量（支） */
    @Column(nullable = false)
    private int capacityItems;

    /** 容量上限：重量（kg） */
    @Column(nullable = false)
    private double capacityWeightKg;

    /** 已投入锐器数量（支） */
    @Column(nullable = false)
    private int usedItems = 0;

    /** 已投入重量（kg） */
    @Column(nullable = false)
    private double usedWeightKg = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SharpContainerStatus status = SharpContainerStatus.OPEN;

    /** 封箱时生成的特殊交接标签编号（SHL 开头），未封箱为空 */
    @Column(length = 32)
    private String handoffLabelNo;

    /** 特殊交接标签照片，逗号分隔 */
    @Column(length = 1024)
    private String labelPhotoUrls;

    private LocalDateTime sealedAt;

    /** 移交所在交接单 */
    private Long handoverOrderId;

    /** 达预警比例时是否已通知（去重，封箱/重置后不再重复通知） */
    @Column(nullable = false)
    private boolean warnNotified = false;

    /** 达容量上限时是否已通知 */
    @Column(nullable = false)
    private boolean fullNotified = false;

    /** 环保公司实收锐器数量（签收时登记） */
    private Integer receivedItems;

    /** 环保公司实收重量（签收时登记） */
    private Double receivedWeightKg;

    @Version
    private long version;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
