package com.community.medrecycle.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 针剂转入锐器盒记录：每次转入登记锐器数量、重量与当时的特殊交接标签。
 * 未封装针剂经现场转入防刺容器后同样在此留痕。
 */
@Getter
@Setter
@Entity
@Table(name = "sharps_deposit", uniqueConstraints = @UniqueConstraint(columnNames = "depositNo"),
        indexes = {
                @Index(name = "idx_deposit_container", columnList = "containerId"),
                @Index(name = "idx_deposit_bag", columnList = "bagId"),
                @Index(name = "idx_deposit_batch", columnList = "batchId")
        })
public class SharpsDeposit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String depositNo;

    @Column(nullable = false)
    private Long containerId;

    @Column(nullable = false, length = 32)
    private String containerNo;

    @Column(nullable = false)
    private Long siteId;

    @Column(nullable = false)
    private Long bagId;

    @Column(nullable = false, length = 32)
    private String bagNo;

    @Column(nullable = false)
    private Long batchId;

    @Column(nullable = false, length = 32)
    private String batchNo;

    /** 本次转入锐器数量（针头/注射器支数） */
    @Column(nullable = false)
    private int sharpsCount;

    @Column(nullable = false)
    private double weightKg;

    /** 转入时的特殊交接标签编号 */
    @Column(nullable = false, length = 32)
    private String labelNo;

    /** 回收员是否已确认使用防刺容器封装 */
    @Column(nullable = false)
    private boolean punctureResistantConfirmed;

    /** 转入照片（防刺容器、针剂、标签），逗号分隔 */
    @Column(length = 1024)
    private String photoUrls;

    /** 是否为破损事故后重新封装转入 */
    @Column(nullable = false)
    private boolean repackaged = false;

    @Column(length = 512)
    private String note;

    @Column(nullable = false)
    private Long confirmedBy;

    @Column(nullable = false)
    private LocalDateTime confirmedAt = LocalDateTime.now();
}
