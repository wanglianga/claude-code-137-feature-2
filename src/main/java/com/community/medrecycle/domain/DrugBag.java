package com.community.medrecycle.domain;

import com.community.medrecycle.domain.enums.BagStatus;
import com.community.medrecycle.domain.enums.DrugCategory;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 封袋：每袋药品有来源（批次/预约）与分类，是暂存与交接的最小单位 */
@Getter
@Setter
@Entity
@Table(name = "drug_bag", uniqueConstraints = {
        @UniqueConstraint(columnNames = "bagNo"),
        @UniqueConstraint(columnNames = "sealNo")
}, indexes = {
        @Index(name = "idx_bag_batch", columnList = "batchId"),
        @Index(name = "idx_bag_site_status", columnList = "siteId,status")
})
public class DrugBag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String bagNo;

    @Column(nullable = false)
    private Long batchId;

    @Column(nullable = false)
    private Long siteId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DrugCategory category;

    @Column(nullable = false)
    private double weightKg;

    /** 封袋编号（现场拍摄记录） */
    @Column(nullable = false, length = 64)
    private String sealNo;

    /** 包装与封袋照片，逗号分隔 */
    @Column(length = 1024)
    private String photoUrls;

    @Column(nullable = false)
    private boolean highRisk;

    /** 本袋应发积分 */
    @Column(nullable = false)
    private int points = 0;

    @Column(nullable = false)
    private boolean pointsGranted = false;

    /** 针剂专用：锐器（针头/注射器）数量，非针剂袋为 0 */
    @Column(nullable = false)
    private int sharpsCount = 0;

    /** 针剂专用：回收员是否确认使用防刺容器封装 */
    @Column(nullable = false)
    private boolean punctureResistant = false;

    /** 针剂专用：是否混入普通药袋（混入须复核处置） */
    @Column(nullable = false)
    private boolean mixedWithNormalBags = false;

    /** 针剂专用：当前所在锐器盒；针剂不入普通暂存柜 */
    private Long sharpsContainerId;

    /** 针剂专用：转入锐器盒时的特殊交接标签 */
    @Column(length = 32)
    private String sharpsLabelNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private BagStatus status = BagStatus.ACCEPTED;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
