package com.community.medrecycle.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** 社区回收点 */
@Getter
@Setter
@Entity
@Table(name = "community_site")
public class CommunitySite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 256)
    private String address;

    /** 暂存柜容量预警比例，如 0.8 表示使用率达 80% 触发预警 */
    @Column(nullable = false)
    private double warnRatio = 0.8;

    /** 环保公司上门最低量（kg），达到该重量才建议发起交接 */
    @Column(nullable = false)
    private double pickupThresholdKg = 50;

    /** 暂存超期天数，超过视为超期药品 */
    @Column(nullable = false)
    private int overdueDays = 30;

    /** 单批交接重量达到该值（kg）时强制双人交接 */
    @Column(nullable = false)
    private double dualWeightThresholdKg = 20;

    /** 锐器盒容量预警比例，使用率达到该值提前通知环保公司或上级回收点 */
    @Column(nullable = false)
    private double sharpWarnRatio = 0.8;
}
