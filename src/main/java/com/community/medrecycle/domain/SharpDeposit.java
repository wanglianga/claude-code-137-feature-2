package com.community.medrecycle.domain;

import com.community.medrecycle.domain.enums.SharpDepositStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 针剂投放记录：居民提交胰岛素针头/注射器时，回收员确认
 * 是否使用防刺容器、是否混入普通药袋、重量与照片后，将已封装针剂转入锐器盒。
 * 未封装针剂不能直接进入普通暂存柜。
 */
@Getter
@Setter
@Entity
@Table(name = "sharp_deposit", indexes = {
        @Index(name = "idx_deposit_container", columnList = "containerId"),
        @Index(name = "idx_deposit_bag", columnList = "bagId"),
        @Index(name = "idx_deposit_batch", columnList = "batchId")
})
public class SharpDeposit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String depositNo;

    @Column(nullable = false)
    private Long containerId;

    @Column(nullable = false)
    private Long batchId;

    @Column(nullable = false, length = 32)
    private String batchNo;

    /** 针剂对应的称重封袋（SYRINGE 高风险袋） */
    @Column(nullable = false)
    private Long bagId;

    @Column(nullable = false, length = 32)
    private String bagNo;

    @Column(nullable = false)
    private Long siteId;

    /** 锐器数量（支：胰岛素针头/注射器） */
    @Column(nullable = false)
    private int itemCount;

    /** 重量（kg），与袋称重一致 */
    @Column(nullable = false)
    private double weightKg;

    /** 回收员确认：居民是否使用防刺容器 */
    @Column(nullable = false)
    private boolean punctureProofContainer;

    /** 回收员确认：是否混入普通药袋 */
    @Column(nullable = false)
    private boolean mixedWithRegularBag;

    /** 现场确认照片（封装/锐器盒/标签），逗号分隔 */
    @Column(length = 1024)
    private String photoUrls;

    @Column(nullable = false)
    private Long confirmedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SharpDepositStatus status = SharpDepositStatus.IN_CONTAINER;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
