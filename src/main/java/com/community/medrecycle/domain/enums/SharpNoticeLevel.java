package com.community.medrecycle.domain.enums;

/** 锐器盒容量通知级别：达预警比例提前通知 / 达容量上限通知 */
public enum SharpNoticeLevel {
    WARN,   // 使用率达预警比例，提前通知安排收运
    FULL    // 已达容量上限，现场停止接收
}
