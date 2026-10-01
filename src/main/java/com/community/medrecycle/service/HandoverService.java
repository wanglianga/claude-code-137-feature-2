package com.community.medrecycle.service;

import com.community.medrecycle.config.BizException;
import com.community.medrecycle.config.NoGen;
import com.community.medrecycle.domain.*;
import com.community.medrecycle.domain.enums.BagStatus;
import com.community.medrecycle.domain.enums.ExceptionType;
import com.community.medrecycle.domain.enums.HandoverStatus;
import com.community.medrecycle.domain.enums.SharpContainerStatus;
import com.community.medrecycle.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/** 环保交接：建单（上门量校验）、签收（双人判定）、完成（普通袋拒收/锐器盒按数量重量签收与破损处置） */
@Service
@RequiredArgsConstructor
public class HandoverService {

    private final HandoverOrderRepository orderRepository;
    private final HandoverItemRepository itemRepository;
    private final DrugBagRepository bagRepository;
    private final CommunitySiteRepository siteRepository;
    private final StorageCabinetRepository cabinetRepository;
    private final StorageEntryRepository entryRepository;
    private final ExceptionService exceptionService;
    private final SharpService sharpService;
    private final SharpContainerRepository sharpContainerRepository;
    private final SharpDepositRepository sharpDepositRepository;
    private final BatchRefresher batchRefresher;

    /**
     * 建交接单：默认取站点全部暂存袋，并自动带出站点已封箱（含特殊交接标签）的锐器盒；
     * 未达环保公司上门量时拒绝（可 force 强制）。
     */
    @Transactional
    public HandoverOrder create(Long siteId, String companyName, String destination,
                                List<String> bagNos, List<String> containerNos, boolean force) {
        CommunitySite site = siteRepository.findById(siteId)
                .orElseThrow(() -> BizException.notFound("社区回收点不存在: " + siteId));

        // 普通暂存袋：bagNos 为 null 时取站点全部，传空列表表示本单不含普通袋
        List<DrugBag> bags;
        if (bagNos == null) {
            bags = bagRepository.findBySiteIdAndStatus(siteId, BagStatus.STORED);
        } else {
            bags = new ArrayList<>();
            for (String bagNo : bagNos) {
                DrugBag bag = bagRepository.findByBagNo(bagNo)
                        .orElseThrow(() -> BizException.notFound("封袋不存在: " + bagNo));
                if (bag.getStatus() != BagStatus.STORED || !bag.getSiteId().equals(siteId)) {
                    throw BizException.conflict("封袋 " + bagNo + " 不在该站点暂存中，状态: " + bag.getStatus());
                }
                bags.add(bag);
            }
        }

        // 针剂：已封箱锐器盒整盒交接；containerNos 为 null 时取站点全部，空列表表示不含锐器盒
        List<SharpContainer> containers;
        if (containerNos == null) {
            containers = sharpService.sealedAtSite(siteId);
        } else {
            containers = new ArrayList<>();
            for (String no : containerNos) {
                SharpContainer container = sharpService.getContainer(no);
                if (!container.getSiteId().equals(siteId)) {
                    throw BizException.badRequest("锐器盒 " + no + " 不属于该站点");
                }
                containers.add(sharpService.requireSealed(container.getId()));
            }
        }

        if (bags.isEmpty() && containers.isEmpty()) {
            throw BizException.conflict("站点暂存柜中没有可交接的药品袋，也没有已封箱的锐器盒");
        }

        double bagsWeight = bags.stream().mapToDouble(DrugBag::getWeightKg).sum();
        double sharpWeight = containers.stream().mapToDouble(SharpContainer::getUsedWeightKg).sum();
        double totalWeight = round(bagsWeight + sharpWeight);
        int sharpItems = containers.stream().mapToInt(SharpContainer::getUsedItems).sum();
        if (totalWeight < site.getPickupThresholdKg() && !force) {
            throw BizException.conflict(String.format(
                    "未达到环保公司上门量：当前 %.1f kg < 阈值 %.1f kg（可传 force=true 强制发起）",
                    totalWeight, site.getPickupThresholdKg()));
        }

        boolean containsHighRiskBag = bags.stream().anyMatch(DrugBag::isHighRisk);
        boolean requiresDual = containsHighRiskBag || !containers.isEmpty()
                || totalWeight >= site.getDualWeightThresholdKg();

        HandoverOrder order = new HandoverOrder();
        order.setOrderNo(NoGen.next("HO"));
        order.setSiteId(siteId);
        order.setCompanyName(companyName);
        order.setDestination(destination);
        order.setTotalWeightKg(totalWeight);
        order.setBagCount(bags.size());
        order.setSharpContainerCount(containers.size());
        order.setSharpItemCount(sharpItems);
        order.setRequiresDual(requiresDual);
        HandoverOrder saved = orderRepository.save(order);

        Set<Long> batchIds = new HashSet<>();
        for (DrugBag bag : bags) {
            HandoverItem item = new HandoverItem();
            item.setOrderId(saved.getId());
            item.setBagId(bag.getId());
            item.setBagNo(bag.getBagNo());
            item.setCategory(bag.getCategory());
            item.setWeightKg(bag.getWeightKg());
            itemRepository.save(item);

            bag.setStatus(BagStatus.HANDOVER_ASSIGNED);
            bagRepository.save(bag);
            batchIds.add(bag.getBatchId());
        }

        for (SharpContainer container : containers) {
            HandoverItem item = new HandoverItem();
            item.setOrderId(saved.getId());
            item.setBagId(null);
            // 明细键用特殊交接标签号，环保公司按标签逐盒核对
            item.setBagNo(container.getHandoffLabelNo());
            item.setSharpContainerId(container.getId());
            item.setSharpContainerNo(container.getContainerNo());
            item.setCategory(com.community.medrecycle.domain.enums.DrugCategory.SYRINGE);
            item.setWeightKg(container.getUsedWeightKg());
            item.setSharpItemCount(container.getUsedItems());
            itemRepository.save(item);

            // 盒内针剂袋随盒进入在途交接
            for (SharpDeposit deposit : sharpDepositRepository.findByContainerIdOrderByCreatedAtAsc(container.getId())) {
                DrugBag bag = bagRepository.findById(deposit.getBagId()).orElseThrow();
                bag.setStatus(BagStatus.HANDOVER_ASSIGNED);
                bagRepository.save(bag);
                batchIds.add(bag.getBatchId());
            }
        }
        batchIds.forEach(batchRefresher::refresh);
        return saved;
    }

    /** 交出方签收：需要双人交接时必须两名不同签收人 */
    @Transactional
    public HandoverOrder sign(Long operatorId, String orderNo) {
        HandoverOrder order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> BizException.notFound("交接单不存在: " + orderNo));
        if (order.getStatus() != HandoverStatus.DRAFT) {
            throw BizException.conflict("交接单当前状态不可签收: " + order.getStatus());
        }
        if (order.getSignerA() == null) {
            order.setSignerA(operatorId);
        } else if (order.getSignerA().equals(operatorId)) {
            throw BizException.conflict("同一签收人不能重复签收");
        } else if (order.getSignerB() == null) {
            order.setSignerB(operatorId);
        } else {
            throw BizException.conflict("签收人已满");
        }

        boolean signedEnough = order.isRequiresDual()
                ? (order.getSignerA() != null && order.getSignerB() != null)
                : order.getSignerA() != null;
        if (signedEnough) {
            order.setStatus(HandoverStatus.IN_TRANSIT);
            order.setShippedAt(LocalDateTime.now());
        }
        return orderRepository.save(order);
    }

    public record ItemResult(String bagNo, boolean accepted, String rejectReason,
                             Integer receivedItems, Double receivedWeightKg,
                             boolean packageDamaged, String damageNote) {
        /** 普通袋的便捷构造 */
        public ItemResult(String bagNo, boolean accepted, String rejectReason) {
            this(bagNo, accepted, rejectReason, null, null, false, null);
        }
    }

    /**
     * 环保公司签收完成：
     * 普通袋逐袋确认，拒收袋回柜并生成 COMPANY_REJECT 异常；
     * 锐器盒按锐器数量与重量确认，包装破损则回收点补充事故记录并重新封装。
     */
    @Transactional
    public HandoverOrder complete(String orderNo, String receiverName, String photoUrls,
                                  List<ItemResult> itemResults) {
        HandoverOrder order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> BizException.notFound("交接单不存在: " + orderNo));
        if (order.getStatus() != HandoverStatus.IN_TRANSIT) {
            throw BizException.conflict("交接单未处于待签收状态: " + order.getStatus());
        }
        Map<String, ItemResult> resultMap = new HashMap<>();
        if (itemResults != null) {
            for (ItemResult r : itemResults) {
                resultMap.put(r.bagNo(), r);
            }
        }

        List<HandoverItem> items = itemRepository.findByOrderId(order.getId());
        boolean anyRejected = false;
        Set<Long> batchIds = new HashSet<>();

        for (HandoverItem item : items) {
            ItemResult result = resultMap.get(item.getBagNo());

            if (item.getSharpContainerId() != null) {
                // 针剂特殊交接：整盒按锐器数量与重量签收
                SharpContainer container = sharpContainerRepository.findById(item.getSharpContainerId())
                        .orElseThrow();
                boolean damaged = result != null && result.packageDamaged();
                boolean accepted = !damaged && (result == null || result.accepted());

                if (accepted) {
                    int receivedItems = result != null && result.receivedItems() != null
                            ? result.receivedItems() : item.getSharpItemCount();
                    double receivedWeight = result != null && result.receivedWeightKg() != null
                            ? result.receivedWeightKg() : item.getWeightKg();
                    if (receivedItems < 0 || receivedWeight < 0) {
                        throw BizException.badRequest("签收数量/重量不能为负");
                    }
                    item.setAccepted(true);
                    item.setReceivedItems(receivedItems);
                    item.setReceivedWeightKg(receivedWeight);
                    itemRepository.save(item);
                    sharpService.confirmHandedOver(container, receivedItems, receivedWeight, order.getId());
                    for (SharpDeposit deposit : sharpDepositRepository.findByContainerIdOrderByCreatedAtAsc(container.getId())) {
                        batchIds.add(bagRepository.findById(deposit.getBagId()).orElseThrow().getBatchId());
                    }
                } else {
                    anyRejected = true;
                    item.setAccepted(false);
                    if (damaged) {
                        item.setPackageDamaged(true);
                        item.setDamageNote(result.damageNote());
                        // 包装破损：回收点补充事故记录、停用锐器盒、针剂退回重新封装
                        ExceptionRecord accident = sharpService.reportPackageDamage(
                                null, container.getId(), order.getId(), result.damageNote());
                        item.setRejectReason("包装破损，事故记录 " + accident.getExceptionNo()
                                + "，回收点重新封装后再交接");
                        for (SharpDeposit deposit : sharpDepositRepository.findByContainerIdOrderByCreatedAtAsc(container.getId())) {
                            batchIds.add(bagRepository.findById(deposit.getBagId()).orElseThrow().getBatchId());
                        }
                    } else {
                        item.setRejectReason(result != null ? result.rejectReason() : "未说明");
                        rejectSharpContainer(container, item, result != null ? result.rejectReason() : "未说明", batchIds);
                    }
                    itemRepository.save(item);
                }
                continue;
            }

            DrugBag bag = bagRepository.findById(item.getBagId()).orElseThrow();
            boolean accepted = result == null || result.accepted();
            if (accepted) {
                item.setAccepted(true);
                bag.setStatus(BagStatus.HANDED_OVER);
                bagRepository.save(bag);
                releaseStorage(bag);
            } else {
                anyRejected = true;
                item.setAccepted(false);
                item.setRejectReason(result.rejectReason());
                // 拒收袋退回暂存，等待后续处理
                bag.setStatus(BagStatus.STORED);
                bagRepository.save(bag);
                exceptionService.recordAuto(ExceptionType.COMPANY_REJECT, bag,
                        "环保公司拒收 " + bag.getCategory() + " 袋 " + bag.getBagNo()
                                + "，原因: " + (result.rejectReason() == null ? "未说明" : result.rejectReason()));
            }
            itemRepository.save(item);
            batchIds.add(bag.getBatchId());
        }

        order.setReceiverName(receiverName);
        order.setPhotoUrls(photoUrls);
        order.setStatus(anyRejected ? HandoverStatus.PARTIAL_REJECTED : HandoverStatus.COMPLETED);
        if (anyRejected) {
            order.setRejectNote("部分药品/锐器盒未正常签收（拒收或包装破损），详见异常记录");
        }
        order.setCompletedAt(LocalDateTime.now());
        HandoverOrder saved = orderRepository.save(order);

        batchIds.forEach(batchRefresher::refresh);
        return saved;
    }

    /** 锐器盒整盒被拒（非破损）：盒退回封箱待交接状态，针剂袋退回锐器盒暂存 */
    private void rejectSharpContainer(SharpContainer container, HandoverItem item, String reason,
                                      Set<Long> batchIds) {
        exceptionService.recordContainerReject(container, reason);
        container.setStatus(SharpContainerStatus.SEALED);
        container.setHandoverOrderId(null);
        sharpContainerRepository.save(container);
        for (SharpDeposit deposit : sharpDepositRepository.findByContainerIdOrderByCreatedAtAsc(container.getId())) {
            DrugBag bag = bagRepository.findById(deposit.getBagId()).orElseThrow();
            bag.setStatus(BagStatus.SHARP_STORED);
            bagRepository.save(bag);
            batchIds.add(bag.getBatchId());
        }
    }

    /** 袋出柜：关闭暂存记录并释放柜容量 */
    private void releaseStorage(DrugBag bag) {
        entryRepository.findByBagIdAndOutAtIsNull(bag.getId()).ifPresent(entry -> {
            entry.setOutAt(LocalDateTime.now());
            entryRepository.save(entry);
            StorageCabinet cabinet = cabinetRepository.lockById(entry.getCabinetId()).orElseThrow();
            cabinet.setUsedBags(Math.max(0, cabinet.getUsedBags() - 1));
            cabinet.setUsedWeightKg(Math.max(0, cabinet.getUsedWeightKg() - bag.getWeightKg()));
            cabinetRepository.save(cabinet);
        });
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(String orderNo) {
        HandoverOrder order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> BizException.notFound("交接单不存在: " + orderNo));
        List<HandoverItem> items = itemRepository.findByOrderId(order.getId());

        // 积分发放结果：按批次汇总（含锐器盒内针剂袋）
        Map<Long, Map<String, Object>> batchPoints = new LinkedHashMap<>();
        for (HandoverItem item : items) {
            if (item.getSharpContainerId() != null) {
                for (SharpDeposit deposit : sharpDepositRepository.findByContainerIdOrderByCreatedAtAsc(item.getSharpContainerId())) {
                    DrugBag bag = bagRepository.findById(deposit.getBagId()).orElseThrow();
                    accumulateBatchPoints(batchPoints, bag);
                }
            } else {
                DrugBag bag = bagRepository.findById(item.getBagId()).orElseThrow();
                accumulateBatchPoints(batchPoints, bag);
            }
        }

        // 锐器盒签收汇总（按数量与重量）
        List<Map<String, Object>> sharpResult = new ArrayList<>();
        for (HandoverItem item : items) {
            if (item.getSharpContainerId() != null) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("handoffLabelNo", item.getBagNo());
                m.put("containerNo", item.getSharpContainerNo());
                m.put("sealedItems", item.getSharpItemCount());
                m.put("sealedWeightKg", item.getWeightKg());
                m.put("receivedItems", item.getReceivedItems());
                m.put("receivedWeightKg", item.getReceivedWeightKg());
                m.put("accepted", item.getAccepted());
                m.put("packageDamaged", item.isPackageDamaged());
                m.put("note", item.getDamageNote() != null ? item.getDamageNote() : item.getRejectReason());
                sharpResult.add(m);
            }
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("order", order);
        detail.put("items", items);
        detail.put("pointsResult", new ArrayList<>(batchPoints.values()));
        detail.put("sharpContainers", sharpResult);
        return detail;
    }

    private void accumulateBatchPoints(Map<Long, Map<String, Object>> batchPoints, DrugBag bag) {
        Map<String, Object> bp = batchPoints.computeIfAbsent(bag.getBatchId(), k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("batchId", bag.getBatchId());
            m.put("totalPoints", 0);
            m.put("allGranted", true);
            return m;
        });
        bp.put("totalPoints", (int) bp.get("totalPoints") + (bag.isPointsGranted() ? bag.getPoints() : 0));
        bp.put("allGranted", (boolean) bp.get("allGranted") && bag.isPointsGranted());
    }

    @Transactional(readOnly = true)
    public List<HandoverOrder> listBySite(Long siteId) {
        return orderRepository.findBySiteIdOrderByCreatedAtDesc(siteId);
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}