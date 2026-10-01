package com.community.medrecycle.domain.enums;

/** 锐器盒容量预警通知类型 */
public enum SharpsNotifyType {
    NEAR_FULL, // 容量达到预警比例，提前通知环保公司/上级回收点
    FULL       // 达到容量上限，停止现场接收
}
