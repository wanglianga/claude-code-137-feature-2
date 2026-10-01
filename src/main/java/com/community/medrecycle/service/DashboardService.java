package com.community.medrecycle.service;

import com.community.medrecycle.config.BizException;
import com.community.medrecycle.domain.*;
import com.community.medrecycle.domain.enums.BagStatus;
import com.community.medrecycle.domain.enums.ExceptionStatus;
import com.community.medrecycle.domain.enums.ReviewStatus;
import com.community.medrecycle.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 社区管理员看板：超期、双人交接、上门量、容量预警一屏掌握 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final CommunitySiteRepository siteRepository;
    private final StorageCabinetRepository cabinetRepository;
    private final StorageEntryRepository entryRepository;
    private final DrugBagRepository bagRepository;
    private final ReviewTaskRepository reviewTaskRepository;
    private final ExceptionRecordRepository exceptionRepository;
    private final SharpContainerRepository sharpContainerRepository;
    private final StorageService storageService;

    @Transactional(readOnly = true)
    public Map<String, Object> dashboard(Long siteId) {
        CommunitySite site = siteRepository.findById(siteId)
                .orElseThrow(() -> BizException.notFound("社区回收点不存在: " + siteId));

        // 暂存超期
        LocalDateTime cutoff = LocalDateTime.now().minusDays(site.getOverdueDays());
        List<StorageEntry> overdueEntries = entryRepository
                .findBySiteIdAndOutAtIsNullAndInAtBefore(siteId, cutoff);

        // 待交接（暂存中）统计
        List<DrugBag> storedBags = bagRepository.findBySiteIdAndStatus(siteId, BagStatus.STORED);
        double readyWeight = storedBags.stream().mapToDouble(DrugBag::getWeightKg).sum();
        boolean anyHighRisk = storedBags.stream().anyMatch(DrugBag::isHighRisk);

        // 已封箱待交接的锐器盒（针剂已在锐器盒内，不在普通暂存柜，但即将随单交接）
        List<com.community.medrecycle.domain.SharpContainer> sealedSharps =
                sharpContainerRepository.findBySiteIdAndStatus(
                        siteId, com.community.medrecycle.domain.enums.SharpContainerStatus.SEALED);
        double sharpReadyWeight = sealedSharps.stream()
                .mapToDouble(com.community.medrecycle.domain.SharpContainer::getUsedWeightKg).sum();
        boolean hasSealedSharps = !sealedSharps.isEmpty();
        double readyWeightTotal = readyWeight + sharpReadyWeight;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("site", site);
        result.put("cabinets", storageService.cabinets(siteId));
        result.put("capacityAlerts", storageService.alerts(siteId));
        result.put("overdue", Map.of(
                "overdueDaysLimit", site.getOverdueDays(),
                "count", overdueEntries.size(),
                "entries", overdueEntries.stream().map(e -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("bagNo", e.getBagNo());
                    m.put("inAt", e.getInAt());
                    m.put("storedDays", ChronoUnit.DAYS.between(e.getInAt(), LocalDateTime.now()));
                    return m;
                }).toList()));
        Map<String, Object> pickup = new LinkedHashMap<>();
        pickup.put("storedBags", storedBags.size());
        pickup.put("storedWeightKg", readyWeight);
        pickup.put("sealedSharpContainers", sealedSharps.size());
        pickup.put("sharpReadyWeightKg", sharpReadyWeight);
        pickup.put("totalReadyWeightKg", readyWeightTotal);
        pickup.put("pickupThresholdKg", site.getPickupThresholdKg());
        pickup.put("thresholdReached", readyWeightTotal >= site.getPickupThresholdKg());
        result.put("pickup", pickup);
        Map<String, Object> handover = new LinkedHashMap<>();
        // 含高风险普通袋、已封箱锐器盒、或总重达阈值，现在发起交接均需双人签收
        handover.put("requiresDualIfHandoverNow",
                anyHighRisk || hasSealedSharps || readyWeightTotal >= site.getDualWeightThresholdKg());
        handover.put("dualWeightThresholdKg", site.getDualWeightThresholdKg());
        handover.put("containsHighRisk", anyHighRisk);
        handover.put("containsSealedSharps", hasSealedSharps);
        result.put("handover", handover);
        result.put("pendingReviews", reviewTaskRepository.findByStatusOrderByCreatedAtAsc(ReviewStatus.PENDING).size());
        result.put("openExceptions", exceptionRepository.countByStatus(ExceptionStatus.OPEN)
                + exceptionRepository.countByStatus(ExceptionStatus.PROCESSING));

        // 针剂锐器盒：使用率预警、达容量上限停止接收、已封箱待交接
        List<Map<String, Object>> sharpViews = new ArrayList<>();
        int sharpFull = 0;
        int sharpWarn = 0;
        int sharpSealed = 0;
        for (com.community.medrecycle.domain.SharpContainer c
                : sharpContainerRepository.findBySiteIdOrderByIdAsc(siteId)) {
            double ratio = com.community.medrecycle.service.SharpService.usageRatio(c);
            String level;
            if (c.getStatus() == com.community.medrecycle.domain.enums.SharpContainerStatus.FULL
                    || c.getStatus() == com.community.medrecycle.domain.enums.SharpContainerStatus.SEALED
                    || ratio >= 1.0) {
                level = "FULL";
            } else if (ratio >= site.getSharpWarnRatio()) {
                level = "WARN";
            } else {
                level = "OK";
            }
            if ("FULL".equals(level)) {
                sharpFull++;
            } else if ("WARN".equals(level)) {
                sharpWarn++;
            }
            if (c.getStatus() == com.community.medrecycle.domain.enums.SharpContainerStatus.SEALED) {
                sharpSealed++;
            }
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("containerNo", c.getContainerNo());
            v.put("name", c.getName());
            v.put("status", c.getStatus());
            v.put("usedItems", c.getUsedItems());
            v.put("capacityItems", c.getCapacityItems());
            v.put("usedWeightKg", c.getUsedWeightKg());
            v.put("capacityWeightKg", c.getCapacityWeightKg());
            v.put("usageRatio", ratio);
            v.put("warning", level);
            v.put("handoffLabelNo", c.getHandoffLabelNo());
            sharpViews.add(v);
        }
        result.put("sharps", Map.of(
                "warnRatio", site.getSharpWarnRatio(),
                "warnCount", sharpWarn,
                "fullCount", sharpFull,
                "sealedReadyCount", sharpSealed,
                "acceptingBlocked", sharpFull > 0,
                "containers", sharpViews));
        return result;
    }
}
