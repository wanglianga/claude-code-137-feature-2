package com.community.medrecycle.domain;

import com.community.medrecycle.domain.enums.HandoverStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 环保交接单 */
@Getter
@Setter
@Entity
@Table(name = "handover_order", uniqueConstraints = @UniqueConstraint(columnNames = "orderNo"))
public class HandoverOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String orderNo;

    @Column(nullable = false)
    private Long siteId;

    /** 环保公司 */
    @Column(nullable = false, length = 64)
    private String companyName;

    /** 批次去向（处理设施/目的地） */
    @Column(nullable = false, length = 256)
    private String destination;

    @Column(nullable = false)
    private double totalWeightKg = 0;

    @Column(nullable = false)
    private int bagCount = 0;

    /** 随单交接的锐器盒数量 */
    @Column(nullable = false)
    private int sharpsContainerCount = 0;

    /** 随单交接锐器（针头/注射器）总数 */
    @Column(nullable = false)
    private int sharpsCount = 0;

    /** 随单交接锐器盒总重量 kg（已计入 totalWeightKg） */
    @Column(nullable = false)
    private double sharpsWeightKg = 0;

    /** 是否需要双人交接（含高风险袋或重量达阈值） */
    @Column(nullable = false)
    private boolean requiresDual = false;

    /** 签收人（交出人），双人时两人都需登记 */
    private Long signerA;

    private Long signerB;

    /** 环保公司签收人姓名 */
    @Column(length = 64)
    private String receiverName;

    /** 交接照片，逗号分隔 */
    @Column(length = 1024)
    private String photoUrls;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private HandoverStatus status = HandoverStatus.DRAFT;

    @Column(length = 256)
    private String rejectNote;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime shippedAt;

    private LocalDateTime completedAt;
}
