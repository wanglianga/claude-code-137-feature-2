package com.community.medrecycle.domain.enums;

/** 锐器盒状态：使用中（可接收）/ 已封盒（待交接）/ 已交接 / 已报废（破损重新封装） */
public enum SharpsStatus {
    IN_USE,      // 使用中，可继续接收针剂
    SEALED,      // 已封盒，待环保交接
    HANDED_OVER, // 已交接给环保公司
    DISPOSED     // 包装破损报废，内容物已重新封装到新盒
}
