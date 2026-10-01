package com.community.medrecycle.service;

import com.community.medrecycle.domain.CommunitySite;
import com.community.medrecycle.domain.SharpsContainer;
import com.community.medrecycle.domain.SharpsNotification;
import com.community.medrecycle.domain.enums.SharpsNotifyType;
import com.community.medrecycle.repository.CommunitySiteRepository;
import com.community.medrecycle.repository.SharpsNotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 锐器盒容量预警通知下发：独立事务提交，避免外层因容量不足回滚导致通知丢失 */
@Component
@RequiredArgsConstructor
public class SharpsNotifier {

    private final SharpsNotificationRepository notificationRepository;
    private final CommunitySiteRepository siteRepository;

    /** 同一锐器盒同一类型仅通知一次；在独立事务中提交 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyNow(SharpsContainer container, SharpsNotifyType type) {
        if (notificationRepository.existsByContainerIdAndType(container.getId(), type)) {
            return;
        }
        CommunitySite site = siteRepository.findById(container.getSiteId()).orElseThrow();
        SharpsNotification n = new SharpsNotification();
        n.setContainerId(container.getId());
        n.setContainerNo(container.getContainerNo());
        n.setSiteId(container.getSiteId());
        n.setType(type);
        n.setTargetName("环保公司/上级回收点");
        double ratio = SharpsService.usageRatio(container);
        n.setContent(type == SharpsNotifyType.FULL
                ? String.format("【%s】锐器盒 %s 已达容量上限（%d/%d 支、%.2f/%.2f kg），"
                        + "现场已停止接收，请尽快安排清运并启用新盒",
                        site.getName(), container.getContainerNo(),
                        container.getUsedSharps(), container.getCapacitySharps(),
                        container.getUsedWeightKg(), container.getCapacityWeightKg())
                : String.format("【%s】锐器盒 %s 使用率已达 %d%%（%d/%d 支、%.2f/%.2f kg），"
                        + "请提前安排清运，避免满盒后无法安全暂存",
                        site.getName(), container.getContainerNo(),
                        Math.round(ratio * 100),
                        container.getUsedSharps(), container.getCapacitySharps(),
                        container.getUsedWeightKg(), container.getCapacityWeightKg()));
        notificationRepository.save(n);
    }
}
