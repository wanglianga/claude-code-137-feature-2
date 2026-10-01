package com.community.medrecycle.domain;

import com.community.medrecycle.domain.enums.DrugCategory;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** 环保交接单明细：每袋的来源分类与签收结果 */
@Getter
@Setter
@Entity
@Table(name = "handover_item", indexes = {
        @Index(name = "idx_item_order", columnList = "orderId"),
        @Index(name = "idx_item_bag", columnList = "bagId")
})
public class HandoverItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    /** 普通药品封袋；锐器盒整盒交接明细为空 */
    private Long bagId;

    @Column(nullable = false, length = 32)
    private String bagNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DrugCategory category;

    @Column(nullable = false)
    private double weightKg;

    /** 针剂特殊交接：该明细关联的锐器盒；普通袋为空 */
    private Long sharpContainerId;

    @Column(length = 32)
    private String sharpContainerNo;

    /** 针剂特殊交接：封箱登记的锐器数量（支），环保公司按此数量与重量签收 */
    private Integer sharpItemCount;

    /** 环保公司实收锐器数量（支） */
    private Integer receivedItems;

    /** 环保公司实收重量（kg），普通袋与袋重一致；针剂为整盒实收重量 */
    private Double receivedWeightKg;

    /** 签收时确认包装是否破损；破损需补充事故记录并重新封装 */
    @Column(nullable = false)
    private boolean packageDamaged = false;

    @Column(length = 256)
    private String damageNote;

    /** 环保公司是否签收，null 表示未确认 */
    private Boolean accepted;

    @Column(length = 256)
    private String rejectReason;
}
