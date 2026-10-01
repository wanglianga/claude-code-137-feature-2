package com.community.medrecycle.service;

import com.community.medrecycle.config.BizException;
import com.community.medrecycle.config.NoGen;
import com.community.medrecycle.domain.*;
import com.community.medrecycle.domain.enums.BagStatus;
import com.community.medrecycle.domain.enums.ExceptionType;
import com.community.medrecycle.domain.enums.SharpsNotifyType;
import com.community.medrecycle.domain.enums.SharpsStatus;
import com.community.medrecycle.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 针剂安全封装复核：针剂（胰岛素针头/注射器）不得进入普通暂存柜，
 * 复核处置后转入锐器盒（防刺容器），转入即绑定特殊交接标签；
 * 锐器盒容量达预警线提前通知环保公司/上级回收点，达上限停止现场接收；
 * 封盒后整体交接，包装破损须补充事故记录并重新封装。
 */
@Service
@RequiredArgsConstructor
public class SharpsService {

    private final SharpsContainerRepository containerRepository;
    private final SharpsDepositRepository depositRepository;
    private final SharpsNotificationRepository notificationRepository;
    private final SharpsNotifier notifier;
    private final DrugBagRepository bagRepository;
    private final RecycleBatchRepository batchRepository;
    private final CommunitySiteRepository siteRepository;
    private final ExceptionRecordRepository exceptionRepository;
    private final BatchRefresher batchRefresher;

    /** 登记锐器盒（社区管理员配置） */
    @Transactional
    public SharpsContainer registerContainer(Long siteId, String name, int capacitySharps,
                                             double capacityWeightKg, Double warnRatio) {
        siteRepository.findById(siteId)
                .orElseThrow(() -> BizException.notFound("社区回收点不存在: " + siteId));
        if (capacitySharps <= 0 || capacityWeightKg <= 0) {
            throw BizException.badRequest("锐器盒容量必须大于 0");
        }
        SharpsContainer container = new SharpsContainer();
        container.setContainerNo(NoGen.next("SC"));
        container.setSiteId(siteId);
        container.setName(name);
        container.setCapacitySharps(capacitySharps);
        container.setCapacityWeightKg(capacityWeightKg);
        if (warnRatio != null) {
            if (warnRatio <= 0 || warnRatio > 1) {
                throw BizException.badRequest("预警比例需在 (0,1] 区间");
            }
            container.setWarnRatio(warnRatio);
        }
        return containerRepository.save(container);
    }

    @Transactional(readOnly = true)
    public List<SharpsContainer> list(Long siteId, SharpsStatus status) {
        if (status != null) {
            return containerRepository.findBySiteIdAndStatus(siteId, status);
        }
        return containerRepository.findBySiteIdOrderByCreatedAtDesc(siteId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(String containerNo) {
        SharpsContainer container = get(containerNo);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("container", container);
        view.put("deposits", depositRepository.findByContainerIdOrderByConfirmedAtAsc(container.getId()));
        view.put("notifications", notificationRepository.findAll().stream()
                .filter(n -> n.getContainerId().equals(container.getId()))
                .sorted(Comparator.comparing(SharpsNotification::getCreatedAt))
                .toList());
        view.put("usageRatio", usageRatio(container));
        view.put("warning", warningLevel(container));
        return view;
    }

    public record DepositResult(String depositNo, String labelNo, int totalSharps,
                                double totalWeightKg, double usageRatio, String warning) {
    }

    /**
     * 针剂袋复核通过后转入锐器盒：强校验容量，绑定/生成特殊交接标签；
     * 达容量上限的锐器盒拒绝接收，避免现场继续接收却无法安全暂存。
     */
    @Transactional
    public DepositResult deposit(Long operatorId, String containerNo, String bagNo,
                                 String photoUrls, String note) {
        DrugBag bag = bagRepository.findByBagNo(bagNo)
                .orElseThrow(() -> BizException.notFound("封袋不存在: " + bagNo));
        if (bag.getCategory() != com.community.medrecycle.domain.enums.DrugCategory.SYRINGE) {
            throw BizException.badRequest("仅针剂（注射器/针头）可转入锐器盒，普通药品入暂存柜");
        }
        if (bag.getStatus() != BagStatus.ACCEPTED) {
            throw BizException.conflict("针剂袋 " + bagNo + " 状态不可转入锐器盒: " + bag.getStatus()
                    + "（须复核通过且尚未转入）");
        }
        if (photoUrls == null || photoUrls.isBlank()) {
            throw BizException.badRequest("转入锐器盒必须上传封装照片");
        }

        SharpsContainer container = containerRepository.lockById(resolveId(containerNo))
                .orElseThrow(() -> BizException.notFound("锐器盒不存在: " + containerNo));
        if (!container.getSiteId().equals(bag.getSiteId())) {
            throw BizException.badRequest("针剂袋 " + bagNo + " 不属于该站点锐器盒");
        }
        if (container.getStatus() != SharpsStatus.IN_USE) {
            throw BizException.conflict("锐器盒 " + containerNo + " 当前状态不可接收: " + container.getStatus());
        }
        if (container.getUsedSharps() + bag.getSharpsCount() > container.getCapacitySharps()
                || container.getUsedWeightKg() + bag.getWeightKg() > container.getCapacityWeightKg()) {
            // 达容量上限：先确保停收通知已独立落库，再拒绝现场接收
            notifier.notifyNow(container, SharpsNotifyType.FULL);
            throw BizException.conflict(String.format(
                    "锐器盒 %s 已达容量上限（%d/%d 支、%.2f/%.2f kg），停止现场接收；"
                            + "已通知环保公司/上级回收点，请封盒交接并启用新锐器盒",
                    container.getContainerNo(), container.getUsedSharps(), container.getCapacitySharps(),
                    container.getUsedWeightKg(), container.getCapacityWeightKg()));
        }

        // 首次转入即生成特殊交接标签（SHL），与锐器盒绑定；重新封装的新盒生成新标签
        if (container.getSpecialLabelNo() == null) {
            container.setSpecialLabelNo(NoGen.next("SHL"));
        }
        RecycleBatch batch = batchRepository.findById(bag.getBatchId()).orElseThrow();

        SharpsDeposit deposit = new SharpsDeposit();
        deposit.setDepositNo(NoGen.next("SHD"));
        deposit.setContainerId(container.getId());
        deposit.setContainerNo(container.getContainerNo());
        deposit.setSiteId(container.getSiteId());
        deposit.setBagId(bag.getId());
        deposit.setBagNo(bag.getBagNo());
        deposit.setBatchId(batch.getId());
        deposit.setBatchNo(batch.getBatchNo());
        deposit.setSharpsCount(bag.getSharpsCount());
        deposit.setWeightKg(bag.getWeightKg());
        deposit.setLabelNo(container.getSpecialLabelNo());
        deposit.setPunctureResistantConfirmed(bag.isPunctureResistant());
        deposit.setPhotoUrls(photoUrls);
        deposit.setNote(note);
        deposit.setConfirmedBy(operatorId);
        depositRepository.save(deposit);

        container.setUsedSharps(container.getUsedSharps() + bag.getSharpsCount());
        container.setUsedWeightKg(round(container.getUsedWeightKg() + bag.getWeightKg()));
        containerRepository.save(container);

        bag.setStatus(BagStatus.IN_SHARPS_BOX);
        bag.setSharpsContainerId(container.getId());
        bag.setSharpsLabelNo(container.getSpecialLabelNo());
        bagRepository.save(bag);

        // 容量预警：达预警线提前通知一次；恰好达上限再补一条满盒通知（独立事务，先于业务提交）
        double ratio = usageRatio(container);
        if (ratio >= 1.0) {
            notifier.notifyNow(container, SharpsNotifyType.FULL);
        } else if (ratio >= container.getWarnRatio()) {
            notifier.notifyNow(container, SharpsNotifyType.NEAR_FULL);
        }

        batchRefresher.refresh(batch.getId());
        return new DepositResult(deposit.getDepositNo(), container.getSpecialLabelNo(),
                container.getUsedSharps(), container.getUsedWeightKg(), ratio, warningLevel(container));
    }

    /** 封盒：盒内须有针剂，封盒后只能交接不能继续接收 */
    @Transactional
    public SharpsContainer seal(String containerNo) {
        SharpsContainer container = get(containerNo);
        if (container.getStatus() != SharpsStatus.IN_USE) {
            throw BizException.conflict("锐器盒当前状态不可封盒: " + container.getStatus());
        }
        if (container.getUsedSharps() == 0) {
            throw BizException.conflict("空锐器盒不可封盒");
        }
        if (container.getSpecialLabelNo() == null) {
            container.setSpecialLabelNo(NoGen.next("SHL"));
        }
        container.setStatus(SharpsStatus.SEALED);
        container.setSealedAt(LocalDateTime.now());
        return containerRepository.save(container);
    }

    public record RepackResult(String oldContainerNo, String newContainerNo, String newLabelNo,
                               int movedSharps, double movedWeightKg, int movedBags,
                               List<String> incidentNos) {
    }

    /**
     * 包装破损重新封装：破损盒内全部针剂转入全新空锐器盒、生成新标签，
     * 原盒报废，并按批次补充事故记录（SHARPS_PACKAGING_DAMAGED）。
     */
    @Transactional
    public RepackResult repackage(Long operatorId, String damagedContainerNo, String newContainerNo,
                                  String photoUrls, String note) {
        SharpsContainer damaged = get(damagedContainerNo);
        if (damaged.getStatus() != SharpsStatus.SEALED) {
            throw BizException.conflict("仅已封盒、签收时发现破损的锐器盒可重新封装，当前状态: "
                    + damaged.getStatus());
        }
        List<SharpsDeposit> oldDeposits =
                depositRepository.findByContainerIdOrderByConfirmedAtAsc(damaged.getId());
        if (oldDeposits.isEmpty()) {
            throw BizException.conflict("破损锐器盒内无针剂记录");
        }

        SharpsContainer fresh = containerRepository.lockById(resolveId(newContainerNo))
                .orElseThrow(() -> BizException.notFound("锐器盒不存在: " + newContainerNo));
        if (!fresh.getSiteId().equals(damaged.getSiteId())) {
            throw BizException.badRequest("新锐器盒与破损盒不属于同一站点");
        }
        if (fresh.getStatus() != SharpsStatus.IN_USE || fresh.getUsedSharps() > 0) {
            throw BizException.conflict("重新封装必须使用站点内全新空锐器盒");
        }
        if (photoUrls == null || photoUrls.isBlank()) {
            throw BizException.badRequest("重新封装必须上传现场照片");
        }

        int moveSharps = oldDeposits.stream().mapToInt(SharpsDeposit::getSharpsCount).sum();
        double moveWeight = round(oldDeposits.stream().mapToDouble(SharpsDeposit::getWeightKg).sum());
        if (moveSharps > fresh.getCapacitySharps() || moveWeight > fresh.getCapacityWeightKg()) {
            throw BizException.conflict("新锐器盒容量不足以容纳重新封装的全部针剂");
        }

        // 新盒生成新的特殊交接标签
        fresh.setSpecialLabelNo(NoGen.next("SHL"));
        Set<Long> batchIds = new LinkedHashSet<>();
        for (SharpsDeposit old : oldDeposits) {
            DrugBag bag = bagRepository.findById(old.getBagId()).orElseThrow();
            SharpsDeposit d = new SharpsDeposit();
            d.setDepositNo(NoGen.next("SHD"));
            d.setContainerId(fresh.getId());
            d.setContainerNo(fresh.getContainerNo());
            d.setSiteId(fresh.getSiteId());
            d.setBagId(bag.getId());
            d.setBagNo(bag.getBagNo());
            d.setBatchId(bag.getBatchId());
            d.setBatchNo(old.getBatchNo());
            d.setSharpsCount(old.getSharpsCount());
            d.setWeightKg(old.getWeightKg());
            d.setLabelNo(fresh.getSpecialLabelNo());
            d.setPunctureResistantConfirmed(true);
            d.setPhotoUrls(photoUrls);
            d.setRepackaged(true);
            d.setNote("由破损盒 " + damaged.getContainerNo() + "（标签 " + damaged.getSpecialLabelNo()
                    + "）重新封装" + (note == null ? "" : "：" + note));
            d.setConfirmedBy(operatorId);
            depositRepository.save(d);

            bag.setSharpsContainerId(fresh.getId());
            bag.setSharpsLabelNo(fresh.getSpecialLabelNo());
            bagRepository.save(bag);
            batchIds.add(bag.getBatchId());
        }
        fresh.setUsedSharps(moveSharps);
        fresh.setUsedWeightKg(moveWeight);
        // 重新封装完成即封盒，等待再次交接
        fresh.setStatus(SharpsStatus.SEALED);
        fresh.setSealedAt(LocalDateTime.now());
        containerRepository.save(fresh);
        if (usageRatio(fresh) >= 1.0) {
            notifier.notifyNow(fresh, SharpsNotifyType.FULL);
        } else if (usageRatio(fresh) >= fresh.getWarnRatio()) {
            notifier.notifyNow(fresh, SharpsNotifyType.NEAR_FULL);
        }

        // 原盒报废
        damaged.setStatus(SharpsStatus.DISPOSED);
        damaged.setDisposedAt(LocalDateTime.now());
        containerRepository.save(damaged);

        // 按批次补充事故记录
        List<String> incidentNos = new ArrayList<>();
        Map<Long, List<SharpsDeposit>> byBatch = new LinkedHashMap<>();
        for (SharpsDeposit d : oldDeposits) {
            byBatch.computeIfAbsent(d.getBatchId(), k -> new ArrayList<>()).add(d);
        }
        for (Map.Entry<Long, List<SharpsDeposit>> e : byBatch.entrySet()) {
            RecycleBatch batch = batchRepository.findById(e.getKey()).orElseThrow();
            ExceptionRecord incident = new ExceptionRecord();
            incident.setExceptionNo(NoGen.next("EX"));
            incident.setBatchId(batch.getId());
            incident.setBatchNo(batch.getBatchNo());
            incident.setType(ExceptionType.SHARPS_PACKAGING_DAMAGED);
            incident.setDescription(String.format(
                    "锐器盒 %s（标签 %s）交接签收时发现包装破损，%d 袋针剂（%s，共 %d 支/%.2fkg）"
                            + "已现场重新封装至新盒 %s（新标签 %s），操作人 %d，照片 %s",
                    damaged.getContainerNo(), damaged.getSpecialLabelNo(),
                    e.getValue().size(),
                    e.getValue().stream().map(SharpsDeposit::getBagNo).reduce((a, b) -> a + "," + b).orElse(""),
                    e.getValue().stream().mapToInt(SharpsDeposit::getSharpsCount).sum(),
                    e.getValue().stream().mapToDouble(SharpsDeposit::getWeightKg).sum(),
                    fresh.getContainerNo(), fresh.getSpecialLabelNo(), operatorId, photoUrls));
            incidentNos.add(exceptionRepository.save(incident).getExceptionNo());
        }

        batchIds.forEach(batchRefresher::refresh);
        return new RepackResult(damaged.getContainerNo(), fresh.getContainerNo(), fresh.getSpecialLabelNo(),
                moveSharps, moveWeight, oldDeposits.size(), incidentNos);
    }

    @Transactional(readOnly = true)
    public List<SharpsNotification> notifications(Long siteId) {
        return notificationRepository.findBySiteIdOrderByCreatedAtDesc(siteId);
    }

    @Transactional(readOnly = true)
    public SharpsContainer get(String containerNo) {
        return containerRepository.findByContainerNo(containerNo)
                .orElseThrow(() -> BizException.notFound("锐器盒不存在: " + containerNo));
    }

    private Long resolveId(String containerNo) {
        return get(containerNo).getId();
    }

    static double usageRatio(SharpsContainer c) {
        double byCount = c.getCapacitySharps() == 0 ? 0
                : (double) c.getUsedSharps() / c.getCapacitySharps();
        double byWeight = c.getCapacityWeightKg() == 0 ? 0
                : c.getUsedWeightKg() / c.getCapacityWeightKg();
        return Math.max(byCount, byWeight);
    }

    static String warningLevel(SharpsContainer c) {
        if (c.getStatus() != SharpsStatus.IN_USE) {
            return c.getStatus().name();
        }
        double ratio = usageRatio(c);
        if (ratio >= 1.0) {
            return "FULL";
        }
        if (ratio >= c.getWarnRatio()) {
            return "WARN";
        }
        return "OK";
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
