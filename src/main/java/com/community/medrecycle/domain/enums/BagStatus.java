package com.community.medrecycle.domain.enums;

public enum BagStatus {
    PENDING_REVIEW,    // 待人工复核（高风险或异常触发）
    REVIEW_REJECTED,   // 复核拒绝（不进入暂存与交接）
    ACCEPTED,          // 已验收，待入柜
    STORED,            // 暂存柜中
    SHARP_STORED,      // 针剂已转入锐器盒安全暂存（不进普通暂存柜）
    HANDOVER_ASSIGNED, // 已分配到交接单
    HANDED_OVER        // 已交接给环保公司
}
