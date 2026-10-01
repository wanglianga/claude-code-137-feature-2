package com.community.medrecycle.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * 环保交接单中的锐器盒确认明细：环保公司签收时按每个锐器盒的
 * 特殊交接标签、锐器数量与重量逐项核对，包装破损可整盒拒收。
 */
@Getter
@Setter
@Entity
@Table(name = "handover_container", indexes = @Index(name = "idx_hc_order", columnList = "orderId"))
public class HandoverContainer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long containerId;

    @Column(nullable = false, length = 32)
    private String containerNo;

    /** 特殊交接标签编号 */
    @Column(nullable = false, length = 32)
    private String labelNo;

    /** 交出方登记锐器数量 */
    @Column(nullable = false)
    private int sharpsCount;

    /** 交出方登记重量 kg */
    @Column(nullable = false)
    private double weightKg;

    /** 环保公司实收锐器数量（签收时核对，默认与交出方一致） */
    private Integer receivedSharpsCount;

    /** 环保公司实收重量 kg */
    private Double receivedWeightKg;

    /** 环保公司是否签收，null 表示未确认 */
    private Boolean accepted;

    @Column(length = 256)
    private String rejectReason;

    /** 签收时发现包装是否破损 */
    @Column(nullable = false)
    private boolean packagingDamaged = false;
}
