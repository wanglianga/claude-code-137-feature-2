package com.community.medrecycle.service;

import com.community.medrecycle.config.BizException;
import com.community.medrecycle.domain.*;
import com.community.medrecycle.domain.enums.BagStatus;
import com.community.medrecycle.domain.enums.DrugCategory;
import com.community.medrecycle.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 暂存柜：入柜容量控制、超期检查、容量预警 */
@Service
@RequiredArgsConstructor
public class StorageService {

    private final StorageCabinetRepository cabinetRepository;
    private final StorageEntryRepository entryRepository;
    private final DrugBagRepository bagRepository;
    private final CommunitySiteRepository siteRepository;
    private final BatchRefresher batchRefresher;

    /** 已验收袋入柜：悲观锁防容量超卖 */
    @Transactional
    public Map<String, Object> putBags(Long cabinetId, List<String> bagNos) {
        StorageCabinet cabinet = cabinetRepository.lockById(cabinetId)
                .orElseThrow(() -> BizException.notFound("暂存柜不存在: " + cabinetId));
        if (bagNos == null || bagNos.isEmpty()) {
            throw BizException.badRequest("袋号列表不能为空");
        }

        List<DrugBag> bags = new ArrayList<>();
        for (String bagNo : bagNos) {
            DrugBag bag = bagRepository.findByBagNo(bagNo)
                    .orElseThrow(() -> BizException.notFound("封袋不存在: " + bagNo));
            if (bag.getStatus() != BagStatus.ACCEPTED) {
                throw BizException.conflict("封袋 " + bagNo + " 状态不可入柜: " + bag.getStatus()
                        + "（高风险袋需先复核通过）");
            }
            if (bag.getCategory() == DrugCategory.SYRINGE) {
                // 针剂安全封装复核：未转入锐器盒的针剂不能进入普通暂存柜
                throw BizException.conflict("封袋 " + bagNo
                        + " 为注射器/针剂，不能进入普通暂存柜，须经安全封装复核后转入锐器盒");
            }
            if (!bag.getSiteId().equals(cabinet.getSiteId())) {
                throw BizException.badRequest("封袋 " + bagNo + " 不属于该站点暂存柜");
            }
            bags.add(bag);
        }

        double addWeight = bags.stream().mapToDouble(DrugBag::getWeightKg).sum();
        if (cabinet.getUsedBags() + bags.size() > cabinet.getCapacityBags()
                || cabinet.getUsedWeightKg() + addWeight > cabinet.getCapacityWeightKg()) {
            throw BizException.conflict(String.format(
                    "暂存柜容量不足：当前 %d/%d 袋、%.1f/%.1f kg，本次需入柜 %d 袋、%.1f kg",
                    cabinet.getUsedBags(), cabinet.getCapacityBags(),
                    cabinet.getUsedWeightKg(), cabinet.getCapacityWeightKg(),
                    bags.size(), addWeight));
        }

        Set<Long> batchIds = new HashSet<>();
        for (DrugBag bag : bags) {
            bag.setStatus(BagStatus.STORED);
            bagRepository.save(bag);

            StorageEntry entry = new StorageEntry();
            entry.setCabinetId(cabinet.getId());
            entry.setBagId(bag.getId());
            entry.setBagNo(bag.getBagNo());
            entry.setSiteId(cabinet.getSiteId());
            entryRepository.save(entry);
            batchIds.add(bag.getBatchId());
        }
        cabinet.setUsedBags(cabinet.getUsedBags() + bags.size());
        cabinet.setUsedWeightKg(cabinet.getUsedWeightKg() + addWeight);
        cabinetRepository.save(cabinet);

        batchIds.forEach(batchRefresher::refresh);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cabinet", cabinet);
        result.put("storedBags", bags.size());
        result.put("storedWeightKg", addWeight);
        result.put("usageRatio", usageRatio(cabinet));
        result.put("warning", warningLevel(cabinet));
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> cabinets(Long siteId) {
        CommunitySite site = siteRepository.findById(siteId)
                .orElseThrow(() -> BizException.notFound("社区回收点不存在: " + siteId));
        List<Map<String, Object>> views = new ArrayList<>();
        for (StorageCabinet cabinet : cabinetRepository.findBySiteId(siteId)) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("cabinet", cabinet);
            view.put("usageRatio", usageRatio(cabinet));
            view.put("warning", warningLevel(cabinet));
            view.put("warnRatio", site.getWarnRatio());
            views.add(view);
        }
        return views;
    }

    /** 超期暂存：入柜超过站点配置的超期天数 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> overdue(Long siteId) {
        CommunitySite site = siteRepository.findById(siteId)
                .orElseThrow(() -> BizException.notFound("社区回收点不存在: " + siteId));
        LocalDateTime cutoff = LocalDateTime.now().minusDays(site.getOverdueDays());
        List<Map<String, Object>> views = new ArrayList<>();
        for (StorageEntry entry : entryRepository.findBySiteIdAndOutAtIsNullAndInAtBefore(siteId, cutoff)) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("entry", entry);
            bagRepository.findById(entry.getBagId()).ifPresent(bag -> view.put("bag", bag));
            view.put("storedDays", ChronoUnit.DAYS.between(entry.getInAt(), LocalDateTime.now()));
            view.put("overdueDaysLimit", site.getOverdueDays());
            views.add(view);
        }
        return views;
    }

    /** 容量预警：使用率 >= 站点预警比例的柜子 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> alerts(Long siteId) {
        List<Map<String, Object>> alerts = new ArrayList<>();
        for (Map<String, Object> view : cabinets(siteId)) {
            String level = (String) view.get("warning");
            if (!"OK".equals(level)) {
                alerts.add(view);
            }
        }
        return alerts;
    }

    static double usageRatio(StorageCabinet cabinet) {
        double byBags = cabinet.getCapacityBags() == 0 ? 0
                : (double) cabinet.getUsedBags() / cabinet.getCapacityBags();
        double byWeight = cabinet.getCapacityWeightKg() == 0 ? 0
                : cabinet.getUsedWeightKg() / cabinet.getCapacityWeightKg();
        return Math.max(byBags, byWeight);
    }

    private String warningLevel(StorageCabinet cabinet) {
        CommunitySite site = siteRepository.findById(cabinet.getSiteId()).orElseThrow();
        double ratio = usageRatio(cabinet);
        if (ratio >= 1.0) {
            return "FULL";
        }
        if (ratio >= site.getWarnRatio()) {
            return "WARN";
        }
        return "OK";
    }
}
