package com.community.medrecycle.service;

import com.community.medrecycle.config.BizException;
import com.community.medrecycle.config.NoGen;
import com.community.medrecycle.domain.*;
import com.community.medrecycle.domain.enums.*;
import com.community.medrecycle.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 针剂安全封装复核：
 * 居民提交胰岛素针头/注射器时回收员确认防刺容器、是否混入普通药袋、数量、重量、照片；
 * 已封装针剂只能转入锐器盒，未封装不得进入普通暂存柜；
 * 锐器盒达预警/容量上限提前通知环保公司或上级回收点；
 * 封箱生成特殊交接标签，交接时按锐器数量与重量签收，包装破损须补事故记录并重新封装。
 */
@Service
@RequiredArgsConstructor
public class SharpService {

    private final SharpContainerRepository containerRepository;
    private final SharpDepositRepository depositRepository;
    private final SharpNoticeRepository noticeRepository;
    private final DrugBagRepository bagRepository;
    private final CommunitySiteRepository siteRepository;
    private final RecycleBatchRepository batchRepository;
    private final ExceptionService exceptionService;
    private final BatchRefresher batchRefresher;

    public record DepositRequest(String containerNo, int itemCount,
                                 boolean punctureProofContainer, boolean mixedWithRegularBag,
                                 String photoUrls) {
    }

    /** 登记锐器盒 */
    @Transactional
    public SharpContainer registerContainer(Long siteId, String name,
                                            int capacityItems, double capacityWeightKg) {
        siteRepository.findById(siteId)
                .orElseThrow(() -> BizException.notFound("社区回收点不存在: " + siteId));
        if (capacityItems <= 0 || capacityWeightKg <= 0) {
            throw BizException.badRequest("锐器盒容量必须大于 0");
        }
        SharpContainer container = new SharpContainer();
        container.setContainerNo(NoGen.next("SC"));
        container.setSiteId(siteId);
        container.setName(name == null || name.isBlank() ? "医用锐器盒" : name);
        container.setCapacityItems(capacityItems);
        container.setCapacityWeightKg(capacityWeightKg);
        return containerRepository.save(container);
    }

    /**
     * 针剂转入锐器盒（安全封装复核的硬关口）：
     * 必须为注射器袋、已复核通过；必须使用防刺容器、未混入普通药袋，否则拒绝并按针剂未安全封装处置。
     */
    @Transactional
    public Map<String, Object> deposit(Long operatorId, String bagNo, DepositRequest req) {
        if (req == null || req.containerNo() == null || req.containerNo().isBlank()) {
            throw BizException.badRequest("必须指定转入的锐器盒");
        }
        if (req.itemCount() <= 0) {
            throw BizException.badRequest("锐器数量必须大于 0");
        }
        if (req.photoUrls() == null || req.photoUrls().isBlank()) {
            throw BizException.badRequest("针剂封装必须提供现场照片");
        }
        DrugBag bag = bagRepository.findByBagNo(bagNo)
                .orElseThrow(() -> BizException.notFound("封袋不存在: " + bagNo));
        if (bag.getCategory() != DrugCategory.SYRINGE) {
            throw BizException.badRequest("仅注射器/针剂袋可转入锐器盒，当前品类: " + bag.getCategory());
        }
        if (bag.getStatus() == BagStatus.SHARP_STORED || bag.getStatus() == BagStatus.HANDOVER_ASSIGNED
                || bag.getStatus() == BagStatus.HANDED_OVER) {
            throw BizException.conflict("针剂袋已转入锐器盒或已交接，不可重复投放: " + bag.getStatus());
        }

        // 未使用防刺容器或混入普通药袋：未安全封装，不能转入，更不能进普通暂存柜
        if (!req.punctureProofContainer() || req.mixedWithRegularBag()) {
            // 独立事务留存事故并退回复核；外层事务随后回滚（不产生投放/容量变化）
            exceptionService.handleUnsafeInjection(bag.getId(), req.itemCount(), bag.getWeightKg(),
                    req.punctureProofContainer(), req.mixedWithRegularBag());
            throw BizException.conflict("针剂未安全封装："
                    + (!req.punctureProofContainer() ? "未使用防刺容器；" : "")
                    + (req.mixedWithRegularBag() ? "混入普通药袋；" : "")
                    + "已生成针剂未封装异常并退回人工复核，现场重新规范封装后再转入锐器盒");
        }
        if (bag.getStatus() == BagStatus.PENDING_REVIEW) {
            throw BizException.conflict("高风险针剂袋尚未复核通过，不能转入锐器盒");
        }
        if (bag.getStatus() == BagStatus.REVIEW_REJECTED) {
            throw BizException.conflict("针剂袋已被复核拒绝，不能转入锐器盒");
        }
        if (bag.getStatus() != BagStatus.ACCEPTED) {
            throw BizException.conflict("针剂袋当前状态不可转入锐器盒: " + bag.getStatus());
        }

        SharpContainer container = containerRepository.lockById(
                        containerRepository.findByContainerNo(req.containerNo())
                                .orElseThrow(() -> BizException.notFound("锐器盒不存在: " + req.containerNo()))
                                .getId())
                .orElseThrow();
        if (!container.getSiteId().equals(bag.getSiteId())) {
            throw BizException.badRequest("锐器盒不属于该封袋所在站点");
        }
        if (container.getStatus() == SharpContainerStatus.DISABLED) {
            throw BizException.conflict("锐器盒已因包装破损停用，请改用其他锐器盒");
        }
        if (container.getStatus() != SharpContainerStatus.OPEN) {
            throw BizException.conflict("锐器盒已达容量上限或已封箱（" + container.getStatus()
                    + "），现场停止接收，请使用新的锐器盒");
        }
        if (container.getUsedItems() + req.itemCount() > container.getCapacityItems()
                || container.getUsedWeightKg() + bag.getWeightKg() > container.getCapacityWeightKg() + 1e-9) {
            throw BizException.conflict(String.format(
                    "锐器盒容量不足：当前 %d/%d 支、%.2f/%.2f kg，本次需投入 %d 支、%.2f kg；请启用新锐器盒",
                    container.getUsedItems(), container.getCapacityItems(),
                    container.getUsedWeightKg(), container.getCapacityWeightKg(),
                    req.itemCount(), bag.getWeightKg()));
        }

        SharpDeposit deposit = new SharpDeposit();
        deposit.setDepositNo(NoGen.next("SD"));
        deposit.setContainerId(container.getId());
        deposit.setBatchId(bag.getBatchId());
        deposit.setBatchNo(batchRepository.findById(bag.getBatchId()).orElseThrow().getBatchNo());
        deposit.setBagId(bag.getId());
        deposit.setBagNo(bag.getBagNo());
        deposit.setSiteId(bag.getSiteId());
        deposit.setItemCount(req.itemCount());
        deposit.setWeightKg(bag.getWeightKg());
        deposit.setPunctureProofContainer(true);
        deposit.setMixedWithRegularBag(false);
        deposit.setPhotoUrls(req.photoUrls());
        deposit.setConfirmedBy(operatorId);
        depositRepository.save(deposit);

        container.setUsedItems(container.getUsedItems() + req.itemCount());
        container.setUsedWeightKg(round(container.getUsedWeightKg() + bag.getWeightKg()));
        boolean becameFull = false;
        if (usageRatio(container) >= 1.0) {
            container.setStatus(SharpContainerStatus.FULL);
            becameFull = true;
        }
        containerRepository.save(container);

        bag.setStatus(BagStatus.SHARP_STORED);
        bag.setSharpContainerId(container.getId());
        bagRepository.save(bag);
        batchRefresher.refresh(bag.getBatchId());

        // 容量预警/上限：提前通知环保公司或上级回收点，避免现场继续接收却无法安全暂存
        List<SharpNotice> notices = new ArrayList<>();
        CommunitySite site = siteRepository.findById(container.getSiteId()).orElseThrow();
        if (becameFull) {
            notices.add(notify(container, site, SharpNoticeLevel.FULL, SharpNoticeTarget.COMPANY,
                    "绿源环保科技", operatorId));
        } else if (usageRatio(container) >= site.getSharpWarnRatio() && !container.isWarnNotified()) {
            notices.add(notify(container, site, SharpNoticeLevel.WARN, SharpNoticeTarget.COMPANY,
                    "绿源环保科技", operatorId));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deposit", deposit);
        result.put("container", containerRepository.save(container));
        result.put("usageRatio", usageRatio(container));
        result.put("notices", notices);
        return result;
    }

    /** 封箱：生成特殊交接标签，封箱后只能随交接单整盒移交 */
    @Transactional
    public SharpContainer seal(Long operatorId, String containerNo, String labelPhotoUrls) {
        SharpContainer container = getContainer(containerNo);
        if (container.getStatus() != SharpContainerStatus.OPEN && container.getStatus() != SharpContainerStatus.FULL) {
            throw BizException.conflict("仅接收中或已达上限的锐器盒可封箱，当前状态: " + container.getStatus());
        }
        if (depositRepository.findByContainerIdOrderByCreatedAtAsc(container.getId()).isEmpty()) {
            throw BizException.conflict("空锐器盒不能封箱交接");
        }
        if (labelPhotoUrls == null || labelPhotoUrls.isBlank()) {
            throw BizException.badRequest("封箱必须拍摄特殊交接标签照片");
        }
        container.setStatus(SharpContainerStatus.SEALED);
        container.setHandoffLabelNo(NoGen.next("SHL"));
        container.setLabelPhotoUrls(labelPhotoUrls);
        container.setSealedAt(LocalDateTime.now());
        return containerRepository.save(container);
    }

    /** 手动补发容量通知（预警/上限，可选通知上级回收点） */
    @Transactional
    public SharpNotice notifyManually(Long operatorId, String containerNo,
                                      SharpNoticeLevel level, SharpNoticeTarget target, String targetName) {
        SharpContainer container = getContainer(containerNo);
        CommunitySite site = siteRepository.findById(container.getSiteId()).orElseThrow();
        return notify(container, site, level, target,
                targetName == null || targetName.isBlank() ? defaultTargetName(target) : targetName, operatorId);
    }

    private SharpNotice notify(SharpContainer container, CommunitySite site, SharpNoticeLevel level,
                               SharpNoticeTarget target, String targetName, Long operatorId) {
        if (level == SharpNoticeLevel.WARN) {
            container.setWarnNotified(true);
        } else {
            container.setFullNotified(true);
        }
        containerRepository.save(container);

        SharpNotice notice = new SharpNotice();
        notice.setNoticeNo(NoGen.next("SNT"));
        notice.setContainerId(container.getId());
        notice.setContainerNo(container.getContainerNo());
        notice.setSiteId(container.getSiteId());
        notice.setLevel(level);
        notice.setTarget(target);
        notice.setTargetName(targetName);
        notice.setUsedItems(container.getUsedItems());
        notice.setCapacityItems(container.getCapacityItems());
        notice.setUsedWeightKg(container.getUsedWeightKg());
        notice.setCapacityWeightKg(container.getCapacityWeightKg());
        notice.setMessage(String.format(
                "锐器盒 %s（%s）使用率 %.0f%%：%d/%d 支、%.2f/%.2f kg。%s，请提前安排收运；现场不得继续接收无法安全暂存的针剂。",
                container.getContainerNo(), container.getName(), usageRatio(container) * 100,
                container.getUsedItems(), container.getCapacityItems(),
                container.getUsedWeightKg(), container.getCapacityWeightKg(),
                level == SharpNoticeLevel.FULL ? "已达容量上限并停止接收" : "已达预警比例"));
        notice.setCreatedBy(operatorId);
        return noticeRepository.save(notice);
    }

    /** 包装破损事故：停用锐器盒、移出针剂袋、退回重新封装、补充事故记录 */
    @Transactional
    public ExceptionRecord reportPackageDamage(Long handlerId, Long containerId, Long orderId, String note) {
        SharpContainer container = containerRepository.findById(containerId)
                .orElseThrow(() -> BizException.notFound("锐器盒不存在: " + containerId));

        ExceptionRecord record = exceptionService.recordPackageDamage(container, orderId, note);

        // 盒内针剂袋移出，退回待重新封装；容量释放由重新投放/停用体现
        for (SharpDeposit deposit : depositRepository.findByContainerIdOrderByCreatedAtAsc(container.getId())) {
            if (deposit.getStatus() == SharpDepositStatus.IN_CONTAINER) {
                deposit.setStatus(SharpDepositStatus.REMOVED);
                depositRepository.save(deposit);
                DrugBag bag = bagRepository.findById(deposit.getBagId()).orElseThrow();
                if (bag.getStatus() != BagStatus.HANDED_OVER) {
                    bag.setStatus(BagStatus.ACCEPTED);
                    bag.setSharpContainerId(null);
                    bagRepository.save(bag);
                    batchRefresher.refresh(bag.getBatchId());
                }
            }
        }
        container.setStatus(SharpContainerStatus.DISABLED);
        containerRepository.save(container);
        return record;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> containers(Long siteId) {
        List<Map<String, Object>> views = new ArrayList<>();
        for (SharpContainer c : containerRepository.findBySiteIdOrderByIdAsc(siteId)) {
            views.add(view(c));
        }
        return views;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(String containerNo) {
        SharpContainer container = getContainer(containerNo);
        Map<String, Object> view = view(container);
        view.put("deposits", depositRepository.findByContainerIdOrderByCreatedAtAsc(container.getId()));
        view.put("notices", noticeRepository.findByContainerIdOrderByCreatedAtAsc(container.getId()));
        return view;
    }

    private Map<String, Object> view(SharpContainer c) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("container", c);
        view.put("usageRatio", usageRatio(c));
        view.put("depositCount", depositRepository.findByContainerIdOrderByCreatedAtAsc(c.getId()).stream()
                .filter(d -> d.getStatus() == SharpDepositStatus.IN_CONTAINER
                        || d.getStatus() == SharpDepositStatus.HANDED_OVER).count());
        return view;
    }

    @Transactional(readOnly = true)
    public List<SharpNotice> notices(Long siteId) {
        return siteId == null ? noticeRepository.findAll() : noticeRepository.findBySiteIdOrderByCreatedAtDesc(siteId);
    }

    @Transactional(readOnly = true)
    public SharpContainer getContainer(String containerNo) {
        return containerRepository.findByContainerNo(containerNo)
                .orElseThrow(() -> BizException.notFound("锐器盒不存在: " + containerNo));
    }

    static double usageRatio(SharpContainer c) {
        double byItems = c.getCapacityItems() == 0 ? 0 : (double) c.getUsedItems() / c.getCapacityItems();
        double byWeight = c.getCapacityWeightKg() == 0 ? 0 : c.getUsedWeightKg() / c.getCapacityWeightKg();
        return Math.max(byItems, byWeight);
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static String defaultTargetName(SharpNoticeTarget target) {
        return target == SharpNoticeTarget.COMPANY ? "绿源环保科技" : "上级回收点";
    }

    // ---- 交接环节由 HandoverService 调用 ----

    @Transactional
    public SharpContainer requireSealed(Long containerId) {
        SharpContainer container = containerRepository.lockById(containerId).orElseThrow();
        if (container.getStatus() != SharpContainerStatus.SEALED) {
            throw BizException.conflict("锐器盒未封箱或已处理，不能交接: " + container.getStatus());
        }
        return container;
    }

    /** 环保公司正常签收：按锐器数量与重量确认，整盒移交 */
    @Transactional
    public void confirmHandedOver(SharpContainer container, Integer receivedItems, Double receivedWeightKg,
                                  Long handoverOrderId) {
        container.setStatus(SharpContainerStatus.HANDED_OVER);
        container.setHandoverOrderId(handoverOrderId);
        container.setReceivedItems(receivedItems != null ? receivedItems : container.getUsedItems());
        container.setReceivedWeightKg(receivedWeightKg != null ? receivedWeightKg : container.getUsedWeightKg());
        containerRepository.save(container);
        for (SharpDeposit deposit : depositRepository.findByContainerIdOrderByCreatedAtAsc(container.getId())) {
            if (deposit.getStatus() == SharpDepositStatus.IN_CONTAINER) {
                deposit.setStatus(SharpDepositStatus.HANDED_OVER);
                depositRepository.save(deposit);
                DrugBag bag = bagRepository.findById(deposit.getBagId()).orElseThrow();
                bag.setStatus(BagStatus.HANDED_OVER);
                bagRepository.save(bag);
            }
        }
    }

    /** 站点封箱中的锐器盒（待交接） */
    @Transactional(readOnly = true)
    public List<SharpContainer> sealedAtSite(Long siteId) {
        return containerRepository.findBySiteIdAndStatus(siteId, SharpContainerStatus.SEALED);
    }
}
