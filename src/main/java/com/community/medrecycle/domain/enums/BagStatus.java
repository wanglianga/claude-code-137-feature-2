package com.community.medrecycle.domain.enums;

public enum BagStatus {
    PENDING_REVIEW,    // 待人工复核（高风险或异常触发）
    REVIEW_REJECTED,   // 复核拒绝（不进入暂存与交接）
    ACCEPTED,          // 已验收，待入柜（针剂袋为待转入锐器盒）
    IN_SHARPS_BOX,     // 针剂已转入锐器盒（不进普通暂存柜）
    STORED,            // 暂存柜中
    HANDOVER_ASSIGNED, // 已分配到交接单
    HANDED_OVER        // 已交接给环保公司
}
