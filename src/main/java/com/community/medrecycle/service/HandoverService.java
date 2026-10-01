package com.community.medrecycle.service;

import com.community.medrecycle.config.BizException;
import com.community.medrecycle.config.NoGen;
import com.community.medrecycle.domain.*;
import com.community.medrecycle.domain.enums.BagStatus;
import com.community.medrecycle.domain.enums.ExceptionType;
import com.community.medrecycle.domain.enums.HandoverStatus;
import com.community.medrecycle.domain.enums.SharpsStatus;
import com.community.medrecycle.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/** 环保交接：建单（上门量校验、普通袋+封盒锐器盒）、签收（双人判定）、完成（逐袋/逐盒确认、破损处置） */
@Service
@RequiredArgsConstructor
public class HandoverService {

    private final HandoverOrderRepository orderRepository;
    private final HandoverItemRepository itemRepository;
    private final HandoverContainerRepository containerItemRepository;
    private final SharpsContainerRepository sharpsContainerRepository;
    private final SharpsDepositRepository sharpsDepositRepository;
    private final DrugBagRepository bagRepository;
    private final CommunitySiteRepository siteRepository;
    private final StorageCabinetRepository cabinetRepository;
    private final StorageEntryRepository entryRepository;
    private final ExceptionService exceptionService;
    private final BatchRefresher batchRefresher;

    /**
     * 建交接单：默认取站点全部暂存袋 + 全部已封盒锐器盒；
     * 未达环保公司上门量时拒绝（可 force 强制）。针剂随锐器盒整体交接，不逐袋入单。
     */
    @Transactional
    public HandoverOrder create(Long siteId, String companyName, String destination,
                                List<String> bagNos, List<String> sharpsContainerNos, boolean force) {
        CommunitySite site = siteRepository.findById(siteId)
                .orElseThrow(() -> BizException.notFound("社区回收点不存在: " + siteId));

        List<DrugBag> bags;
        if (bagNos == null) {
            // 未指定：默认取站点全部暂存袋；显式传空列表表示本单不含普通袋（锐器专项交接）
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

        List<SharpsContainer> containers;
        if (sharpsContainerNos == null) {
            // 未指定：默认带上全部已封盒、未破损、未占用的锐器盒；空列表表示本单不含锐器盒
            containers = sharpsContainerRepository
                    .findBySiteIdAndStatusAndPackagingDamagedFalse(siteId, SharpsStatus.SEALED).stream()
                    .filter(c -> c.getAssignedOrderId() == null)
                    .toList();
        } else {
            containers = new ArrayList<>();
            for (String no : sharpsContainerNos) {
                SharpsContainer c = sharpsContainerRepository.findByContainerNo(no)
                        .orElseThrow(() -> BizException.notFound("锐器盒不存在: " + no));
                if (!c.getSiteId().equals(siteId)) {
                    throw BizException.badRequest("锐器盒 " + no + " 不属于该站点");
                }
                if (c.getStatus() != SharpsStatus.SEALED) {
                    throw BizException.conflict("锐器盒 " + no + " 未封盒，当前状态: " + c.getStatus());
                }
                if (c.isPackagingDamaged()) {
                    throw BizException.conflict("锐器盒 " + no + " 包装破损，须先重新封装再交接");
                }
                if (c.getAssignedOrderId() != null) {
                    throw BizException.conflict("锐器盒 " + no + " 已在交接单中，不可重复建单");
                }
                containers.add(c);
            }
        }

        if (bags.isEmpty() && containers.isEmpty()) {
            throw BizException.conflict("站点没有可交接的暂存药品或已封盒锐器盒");
        }

        double bagsWeight = bags.stream().mapToDouble(DrugBag::getWeightKg).sum();
        int sharpsCount = containers.stream().mapToInt(SharpsContainer::getUsedSharps).sum();
        double sharpsWeight = round(containers.stream().mapToDouble(SharpsContainer::getUsedWeightKg).sum());
        double totalWeight = round(bagsWeight + sharpsWeight);
        if (totalWeight < site.getPickupThresholdKg() && !force) {
            throw BizException.conflict(String.format(
                    "未达到环保公司上门量：当前 %.1f kg < 阈值 %.1f kg（可传 force=true 强制发起）",
                    totalWeight, site.getPickupThresholdKg()));
        }

        // 含高风险袋、含锐器盒或超重均自动要求双人签收
        boolean requiresDual = bags.stream().anyMatch(DrugBag::isHighRisk)
                || !containers.isEmpty()
                || totalWeight >= site.getDualWeightThresholdKg();

        HandoverOrder order = new HandoverOrder();
        order.setOrderNo(NoGen.next("HO"));
        order.setSiteId(siteId);
        order.setCompanyName(companyName);
        order.setDestination(destination);
        order.setTotalWeightKg(totalWeight);
        order.setBagCount(bags.size());
        order.setSharpsContainerCount(containers.size());
        order.setSharpsCount(sharpsCount);
        order.setSharpsWeightKg(sharpsWeight);
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
        for (SharpsContainer c : containers) {
            HandoverContainer hc = new HandoverContainer();
            hc.setOrderId(saved.getId());
            hc.setContainerId(c.getId());
            hc.setContainerNo(c.getContainerNo());
            hc.setLabelNo(c.getSpecialLabelNo());
            hc.setSharpsCount(c.getUsedSharps());
            hc.setWeightKg(c.getUsedWeightKg());
            containerItemRepository.save(hc);

            c.setAssignedOrderId(saved.getId());
            sharpsContainerRepository.save(c);
            for (SharpsDeposit d : sharpsDepositRepository.findByContainerIdOrderByConfirmedAtAsc(c.getId())) {
                batchIds.add(d.getBatchId());
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

    public record ItemResult(String bagNo, boolean accepted, String rejectReason) {
    }

    /** 环保公司对单个锐器盒的签收确认：按标签核对锐器数量与重量，可登记包装破损 */
    public record ContainerResult(String containerNo, boolean accepted,
                                  Integer receivedSharpsCount, Double receivedWeightKg,
                                  boolean packagingDamaged, String rejectReason) {
    }

    /** 环保公司签收完成：逐袋/逐盒确认；拒收袋回柜；锐器盒破损须回收点重新封装 */
    @Transactional
    public HandoverOrder complete(String orderNo, String receiverName, String photoUrls,
                                  List<ItemResult> itemResults, List<ContainerResult> containerResults) {
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
        Map<String, ContainerResult> containerMap = new HashMap<>();
        if (containerResults != null) {
            for (ContainerResult r : containerResults) {
                containerMap.put(r.containerNo(), r);
            }
        }

        List<HandoverItem> items = itemRepository.findByOrderId(order.getId());
        List<HandoverContainer> containerItems = containerItemRepository.findByOrderId(order.getId());
        boolean anyRejected = false;
        Set<Long> batchIds = new HashSet<>();
        List<String> damagedContainerNos = new ArrayList<>();

        for (HandoverItem item : items) {
            DrugBag bag = bagRepository.findById(item.getBagId()).orElseThrow();
            ItemResult result = resultMap.get(item.getBagNo());
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

        for (HandoverContainer hc : containerItems) {
            SharpsContainer container = sharpsContainerRepository.findById(hc.getContainerId()).orElseThrow();
            ContainerResult result = containerMap.get(hc.getContainerNo());
            boolean accepted = result == null || result.accepted();

            if (accepted) {
                // 按锐器数量与重量确认（未填报则默认与交出方登记一致）
                hc.setAccepted(true);
                hc.setReceivedSharpsCount(result == null || result.receivedSharpsCount() == null
                        ? hc.getSharpsCount() : result.receivedSharpsCount());
                hc.setReceivedWeightKg(result == null || result.receivedWeightKg() == null
                        ? hc.getWeightKg() : result.receivedWeightKg());
                container.setStatus(SharpsStatus.HANDED_OVER);
                container.setPackagingDamaged(false);
                container.setAssignedOrderId(null);
                container.setHandedOverAt(LocalDateTime.now());
                sharpsContainerRepository.save(container);
                // 盒内针剂袋随盒交接完成
                for (SharpsDeposit d : sharpsDepositRepository.findByContainerIdOrderByConfirmedAtAsc(container.getId())) {
                    DrugBag bag = bagRepository.findById(d.getBagId()).orElseThrow();
                    bag.setStatus(BagStatus.HANDED_OVER);
                    bagRepository.save(bag);
                    batchIds.add(bag.getBatchId());
                }
            } else {
                anyRejected = true;
                hc.setAccepted(false);
                hc.setRejectReason(result == null ? "未说明" : result.rejectReason());
                hc.setPackagingDamaged(result != null && result.packagingDamaged());
                container.setAssignedOrderId(null);
                List<SharpsDeposit> deposits =
                        sharpsDepositRepository.findByContainerIdOrderByConfirmedAtAsc(container.getId());
                if (result != null && result.packagingDamaged()) {
                    // 包装破损：锐器盒退回回收点并冻结，须补充事故记录、重新封装后才能再次交接
                    container.setPackagingDamaged(true);
                    damagedContainerNos.add(container.getContainerNo());
                    for (SharpsDeposit d : deposits) {
                        DrugBag bag = bagRepository.findById(d.getBagId()).orElseThrow();
                        exceptionService.recordAuto(ExceptionType.SHARPS_PACKAGING_DAMAGED, bag,
                                "环保公司签收发现锐器盒 " + container.getContainerNo()
                                        + "（标签 " + container.getSpecialLabelNo() + "）包装破损，整盒退回回收点；"
                                        + "回收点须补充事故记录并重新封装，原因: "
                                        + (result.rejectReason() == null ? "未说明" : result.rejectReason()));
                        batchIds.add(bag.getBatchId());
                    }
                } else {
                    // 非破损拒收（如数量/重量核对不符）：盒退回回收点可重新发起交接
                    container.setPackagingDamaged(false);
                    for (SharpsDeposit d : deposits) {
                        DrugBag bag = bagRepository.findById(d.getBagId()).orElseThrow();
                        exceptionService.recordAuto(ExceptionType.COMPANY_REJECT, bag,
                                "环保公司拒收锐器盒 " + container.getContainerNo()
                                        + "（标签 " + container.getSpecialLabelNo() + "），原因: "
                                        + (result == null || result.rejectReason() == null
                                        ? "未说明" : result.rejectReason()));
                        batchIds.add(bag.getBatchId());
                    }
                }
                sharpsContainerRepository.save(container);
            }
            containerItemRepository.save(hc);
        }

        order.setReceiverName(receiverName);
        order.setPhotoUrls(photoUrls);
        order.setStatus(anyRejected ? HandoverStatus.PARTIAL_REJECTED : HandoverStatus.COMPLETED);
        if (anyRejected) {
            String note = "部分药品/锐器盒被环保公司拒收，详见异常记录";
            if (!damagedContainerNos.isEmpty()) {
                note += "；包装破损待重新封装锐器盒: " + String.join(",", damagedContainerNos);
            }
            order.setRejectNote(note);
        }
        order.setCompletedAt(LocalDateTime.now());
        HandoverOrder saved = orderRepository.save(order);

        batchIds.forEach(batchRefresher::refresh);
        return saved;
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
        List<HandoverContainer> containers = containerItemRepository.findByOrderId(order.getId());

        // 积分发放结果：按批次汇总（含锐器盒内针剂袋）
        Map<Long, Map<String, Object>> batchPoints = new LinkedHashMap<>();
        for (HandoverItem item : items) {
            DrugBag bag = bagRepository.findById(item.getBagId()).orElseThrow();
            accumulateBatchPoints(batchPoints, bag);
        }
        for (HandoverContainer hc : containers) {
            for (SharpsDeposit d : sharpsDepositRepository.findByContainerIdOrderByConfirmedAtAsc(hc.getContainerId())) {
                bagRepository.findById(d.getBagId()).ifPresent(bag -> accumulateBatchPoints(batchPoints, bag));
            }
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("order", order);
        detail.put("items", items);
        detail.put("sharpsContainers", containers);
        detail.put("pointsResult", new ArrayList<>(batchPoints.values()));
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
