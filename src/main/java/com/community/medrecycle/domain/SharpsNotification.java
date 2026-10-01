package com.community.medrecycle.domain;

import com.community.medrecycle.domain.enums.SharpsNotifyType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 锐器盒容量预警通知：达到预警比例时提前通知环保公司或上级回收点安排清运；
 * 达到容量上限时通知停止现场接收，避免继续接收却无法安全暂存。
 * 同一锐器盒同一类型只通知一次（重新封装启用新盒后重新计算）。
 */
@Getter
@Setter
@Entity
@Table(name = "sharps_notification", uniqueConstraints = @UniqueConstraint(
        columnNames = {"containerId", "type"}), indexes = @Index(name = "idx_sharps_notify", columnList = "siteId"))
public class SharpsNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long containerId;

    @Column(nullable = false, length = 32)
    private String containerNo;

    @Column(nullable = false)
    private Long siteId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SharpsNotifyType type;

    /** 通知接收方：环保公司 / 上级回收点名称 */
    @Column(nullable = false, length = 64)
    private String targetName;

    /** 通知方式：SYSTEM（系统内代办，实际部署可对接短信/企业微信） */
    @Column(nullable = false, length = 16)
    private String channel = "SYSTEM";

    @Column(nullable = false, length = 512)
    private String content;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
