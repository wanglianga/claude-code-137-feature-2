package com.community.medrecycle.domain.enums;

/** 异常类型：夹带未过期药 / 处方信息泄露 / 针剂未安全封装 / 液体泄漏 / 称重争议 / 积分兑换失败 / 环保公司拒收 / 锐器包装破损 */
public enum ExceptionType {
    NON_EXPIRED_DRUG,
    PRESCRIPTION_INFO_EXPOSED,
    UNSAFE_INJECTION,
    SHARPS_PACKAGING_DAMAGED,
    LIQUID_LEAK,
    WEIGHT_DISPUTE,
    EXCHANGE_FAILED,
    COMPANY_REJECT
}
