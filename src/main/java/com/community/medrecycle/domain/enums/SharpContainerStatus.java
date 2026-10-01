package com.community.medrecycle.domain.enums;

/** 锐器盒（防刺容器）状态：开放接收 -> 达容量上限 -> 封箱待交接 -> 已随交接单移交环保公司 */
public enum SharpContainerStatus {
    OPEN,          // 接收中
    FULL,          // 达到容量上限，停止现场接收
    SEALED,        // 已封箱并生成特殊交接标签，等待交接
    HANDED_OVER,   // 已随交接单移交环保公司
    DISABLED       // 包装破损后停用，内容物已重新封装转入其他锐器盒
}
