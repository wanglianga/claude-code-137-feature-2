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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private BagStatus status = BagStatus.ACCEPTED;

    /** 针剂转入锐器盒后记录所在锐器盒；普通袋为空 */
    private Long sharpContainerId;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
