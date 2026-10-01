package com.community.medrecycle.domain;

import com.community.medrecycle.domain.enums.SharpNoticeLevel;
import com.community.medrecycle.domain.enums.SharpNoticeTarget;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 锐器盒容量通知：使用率达预警比例或容量上限时，
 * 提前通知环保公司或上级回收点，避免现场继续接收却无法安全暂存。
 */
@Getter
@Setter
@Entity
@Table(name = "sharp_notice", indexes = @Index(name = "idx_notice_container", columnList = "containerId"))
public class SharpNotice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String noticeNo;

    @Column(nullable = false)
    private Long containerId;

    @Column(nullable = false, length = 32)
    private String containerNo;

    @Column(nullable = false)
    private Long siteId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SharpNoticeLevel level;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SharpNoticeTarget target;

    /** 通知对象名称（环保公司名 / 上级回收点名） */
    @Column(nullable = false, length = 128)
    private String targetName;

    @Column(nullable = false)
    private int usedItems;

    @Column(nullable = false)
    private int capacityItems;

    @Column(nullable = false)
    private double usedWeightKg;

    @Column(nullable = false)
    private double capacityWeightKg;

    /** 通知内容（系统生成，说明容量与停止接收状态） */
    @Column(nullable = false, length = 512)
    private String message;

    @Column(nullable = false)
    private Long createdBy;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
