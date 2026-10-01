package com.community.medrecycle.domain;

import com.community.medrecycle.domain.enums.DrugCategory;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 现场分类称重记录 */
@Getter
@Setter
@Entity
@Table(name = "weigh_record", indexes = @Index(name = "idx_weigh_batch", columnList = "batchId"))
public class WeighRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long batchId;

    /** 对应封袋，封装确认随针剂袋转入锐器盒时可追溯 */
    private Long bagId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DrugCategory category;

    @Column(nullable = false)
    private double weightKg;

    /** 包装/封袋照片，逗号分隔的 URL */
    @Column(length = 1024)
    private String photoUrls;

    /** 是否存在称重争议 */
    @Column(nullable = false)
    private boolean dispute = false;

    /** 针剂封装箱核：是否使用防刺容器（居民提交胰岛素针头/注射器时回收员确认） */
    @Column(nullable = false)
    private boolean punctureProofContainer = false;

    /** 针剂封装箱核：是否混入普通药袋 */
    @Column(nullable = false)
    private boolean mixedWithRegularBag = false;

    /** 针剂封装箱核：锐器数量（支） */
    @Column(nullable = false)
    private int sharpItemCount = 0;

    @Column(nullable = false)
    private Long weighedBy;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
