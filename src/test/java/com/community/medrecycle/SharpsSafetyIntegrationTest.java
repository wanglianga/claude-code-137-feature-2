package com.community.medrecycle;

import com.community.medrecycle.config.BizException;
import com.community.medrecycle.domain.*;
import com.community.medrecycle.domain.enums.*;
import com.community.medrecycle.repository.*;
import com.community.medrecycle.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 针剂安全封装复核全链路：
 * 现场四要素确认 -> 未封装针剂不入普通柜/异常退回复核 -> 转入锐器盒 ->
 * 容量预警/上限通知、停止接收 -> 封箱特殊标签 -> 双人交接按数量重量签收 ->
 * 包装破损补充事故记录并重新封装 -> 追溯。
 */
@SpringBootTest
class SharpsSafetyIntegrationTest {

    @Autowired AppointmentService appointmentService;
    @Autowired BatchService batchService;
    @Autowired ReviewService reviewService;
    @Autowired StorageService storageService;
    @Autowired HandoverService handoverService;
    @Autowired SharpService sharpService;
    @Autowired AccountRepository accountRepository;
    @Autowired CommunitySiteRepository siteRepository;
    @Autowired StorageCabinetRepository cabinetRepository;
    @Autowired DrugBagRepository bagRepository;
    @Autowired ReviewTaskRepository reviewTaskRepository;
    @Autowired ExceptionRecordRepository exceptionRepository;
    @Autowired RecycleBatchRepository batchRepository;
    @Autowired SharpContainerRepository containerRepository;
    @Autowired SharpDepositRepository depositRepository;
    @Autowired SharpNoticeRepository noticeRepository;

    private Account u(String name) {
        return accountRepository.findByUsername(name).orElseThrow();
    }

    /** 称重一针剂袋并（按需）复核通过，返回袋 */
    private DrugBag weighedSyringeBag(Account resident, Account recycler, Account reviewer,
                                      Long siteId, double weightKg, int count,
                                      boolean punctureProof, boolean mixed, String photo) {
        Appointment appt = appointmentService.create(resident.getId(), siteId,
                "胰岛素针头与注射器", count, true, false, true, false, null);
        RecycleBatch batch = batchService.checkIn(recycler.getId(), appt.getAppointmentNo());
        batchService.weigh(recycler.getId(), batch.getBatchNo(), List.of(
                new BatchService.WeighItem(DrugCategory.SYRINGE, weightKg, null, photo, false,
                        punctureProof, mixed, count)));
        DrugBag bag = bagRepository.findByBatchId(batch.getId()).get(0);
        // 高风险针剂袋需复核通过后才能转入锐器盒
        List<ReviewTask> tasks = reviewTaskRepository.findByBagIdAndStatus(bag.getId(), ReviewStatus.PENDING);
        if (!tasks.isEmpty()) {
            reviewService.decide(reviewer.getId(), tasks.get(0).getTaskNo(), true, "针剂封装核对无误");
        }
        return bagRepository.findById(bag.getId()).orElseThrow();
    }

    @Test
    void syringeWeighRequiresCountAndPhoto() {
        Account resident = u("resident1");
        Account recycler = u("recycler1");
        Long siteId = siteRepository.findAll().get(0).getId();
        Appointment appt = appointmentService.create(resident.getId(), siteId,
                "针头", 1, true, false, true, false, null);
        RecycleBatch batch = batchService.checkIn(recycler.getId(), appt.getAppointmentNo());

        // 未登记锐器数量 -> 拒绝称重
        BizException e1 = assertThrows(BizException.class, () -> batchService.weigh(
                recycler.getId(), batch.getBatchNo(), List.of(
                        new BatchService.WeighItem(DrugCategory.SYRINGE, 0.5, null, "photo://x",
                                false, true, false, 0))));
        assertEquals(400, e1.getStatus());
        // 未提供照片 -> 拒绝称重
        BizException e2 = assertThrows(BizException.class, () -> batchService.weigh(
                recycler.getId(), batch.getBatchNo(), List.of(
                        new BatchService.WeighItem(DrugCategory.SYRINGE, 0.5, null, null,
                                false, true, false, 5))));
        assertEquals(400, e2.getStatus());
    }

    @Test
    void unsafeSyringeBlockedFromCabinetAndSentToReview() {
        Account resident = u("resident1");
        Account recycler = u("recycler1");
        Account reviewer = u("reviewer1");
        Long siteId = siteRepository.findAll().get(0).getId();
        StorageCabinet cabinet = cabinetRepository.findBySiteId(siteId).get(0);
        SharpContainer box = sharpService.registerContainer(siteId, "未封装测试盒", 100, 10);

        // 现场确认未使用防刺容器：称重阶段即生成针剂未封装异常
        DrugBag bag = weighedSyringeBag(resident, recycler, reviewer, siteId, 0.4, 6, false, false, "photo://s1");
        boolean weighRaisedException = exceptionRepository.findByBatchId(bag.getBatchId()).stream()
                .anyMatch(x -> x.getType() == ExceptionType.UNSAFE_INJECTION);
        assertTrue(weighRaisedException, "称重时未封装针剂应自动生成异常");

        // 复核通过后若仍声称未封装，转入被拒且事故留存
        var req = new SharpService.DepositRequest(box.getContainerNo(), 6, false, false, "photo://s1");
        BizException e = assertThrows(BizException.class,
                () -> sharpService.deposit(recycler.getId(), bag.getBagNo(), req));
        assertEquals(409, e.getStatus());

        // 外层投放回滚：无投放记录、容量未增，但独立事务的事故与复核退回保留
        assertTrue(depositRepository.findByBagId(bag.getId()).isEmpty());
        SharpContainer reloaded = containerRepository.findById(box.getId()).orElseThrow();
        assertEquals(0, reloaded.getUsedItems());
        assertEquals(BagStatus.PENDING_REVIEW, bagRepository.findById(bag.getId()).orElseThrow().getStatus());
        long unsafeCount = exceptionRepository.findAll().stream()
                .filter(x -> x.getType() == ExceptionType.UNSAFE_INJECTION
                        && bag.getBagNo().equals(x.getBagNo())).count();
        assertTrue(unsafeCount >= 2, "称重与转入两次未封装均应留痕");

        // 未（重新）转入锐器盒的针剂不得进入普通暂存柜
        BizException cabinetEx = assertThrows(BizException.class,
                () -> storageService.putBags(cabinet.getId(), List.of(bag.getBagNo())));
        assertEquals(409, cabinetEx.getStatus());
    }

    @Test
    void safeSyringeDepositCapacityNoticeSealAndHandoverByCountAndWeight() {
        Account resident = u("resident2");
        Account recycler = u("recycler1");
        Account reviewer = u("reviewer1");
        Account admin = u("admin1");
        Long siteId = siteRepository.findAll().get(0).getId();

        // 容量 10 支 / 1kg 的小锐器盒，预警比例 0.8
        SharpContainer box = sharpService.registerContainer(siteId, "满量测试盒", 10, 1.0);

        // 第一次投放 9 支 0.3kg -> 使用率 90% 达预警，生成 WARN 通知
        DrugBag bag1 = weighedSyringeBag(resident, recycler, reviewer, siteId, 0.3, 9, true, false, "photo://a");
        Map<String, Object> r1 = sharpService.deposit(recycler.getId(), bag1.getBagNo(),
                new SharpService.DepositRequest(box.getContainerNo(), 9, true, false, "photo://a"));
        assertEquals(BagStatus.SHARP_STORED, bagRepository.findById(bag1.getId()).orElseThrow().getStatus());
        List<SharpNotice> warns = noticeRepository.findByContainerIdOrderByCreatedAtAsc(box.getId());
        assertFalse(warns.isEmpty());
        assertEquals(SharpNoticeLevel.WARN, warns.get(0).getLevel());

        // 再投 2 支将超容量（9+2>10）-> 拒绝，现场停止接收，保证不超装
        DrugBag bag2 = weighedSyringeBag(resident, recycler, reviewer, siteId, 0.1, 2, true, false, "photo://b");
        BizException fullEx = assertThrows(BizException.class, () -> sharpService.deposit(
                recycler.getId(), bag2.getBagNo(),
                new SharpService.DepositRequest(box.getContainerNo(), 2, true, false, "photo://b")));
        assertEquals(409, fullEx.getStatus());

        // 1 支恰好装满 -> FULL 并通知环保公司
        DrugBag bag3 = weighedSyringeBag(resident, recycler, reviewer, siteId, 0.1, 1, true, false, "photo://c");
        Map<String, Object> r3 = sharpService.deposit(recycler.getId(), bag3.getBagNo(),
                new SharpService.DepositRequest(box.getContainerNo(), 1, true, false, "photo://c"));
        SharpContainer full = containerRepository.findById(box.getId()).orElseThrow();
        assertEquals(SharpContainerStatus.FULL, full.getStatus());
        assertTrue(noticeRepository.findByContainerIdOrderByCreatedAtAsc(box.getId()).stream()
                .anyMatch(n -> n.getLevel() == SharpNoticeLevel.FULL));

        // FULL 盒继续接收被拒
        DrugBag bag4 = weighedSyringeBag(resident, recycler, reviewer, siteId, 0.1, 1, true, false, "photo://d");
        BizException blocked = assertThrows(BizException.class, () -> sharpService.deposit(
                recycler.getId(), bag4.getBagNo(),
                new SharpService.DepositRequest(box.getContainerNo(), 1, true, false, "photo://d")));
        assertEquals(409, blocked.getStatus());

        // 封箱生成特殊交接标签
        SharpContainer sealed = sharpService.seal(recycler.getId(), box.getContainerNo(), "photo://label");
        assertEquals(SharpContainerStatus.SEALED, sealed.getStatus());
        assertTrue(sealed.getHandoffLabelNo().startsWith("SHL"));

        // 建单只交接该锐器盒（无普通袋），重量不足上门量 force；含锐器盒强制双人
        HandoverOrder order = handoverService.create(siteId, "绿源环保科技", "市危废处理中心",
                List.of(), List.of(sealed.getContainerNo()), true);
        assertEquals(1, order.getSharpContainerCount());
        assertEquals(10, order.getSharpItemCount());
        assertTrue(order.isRequiresDual());
        handoverService.sign(recycler.getId(), order.getOrderNo());
        handoverService.sign(admin.getId(), order.getOrderNo());

        // 环保公司按锐器数量与重量确认（实收 10 支、0.4kg）
        HandoverOrder done = handoverService.complete(order.getOrderNo(), "绿源-张司机", "photo://recv",
                List.of(new HandoverService.ItemResult(sealed.getHandoffLabelNo(), true, null,
                        10, 0.4, false, null)));
        assertEquals(HandoverStatus.COMPLETED, done.getStatus());
        assertEquals(SharpContainerStatus.HANDED_OVER,
                containerRepository.findById(box.getId()).orElseThrow().getStatus());
        assertEquals(BagStatus.HANDED_OVER, bagRepository.findById(bag1.getId()).orElseThrow().getStatus());
        assertEquals(BagStatus.HANDED_OVER, bagRepository.findById(bag3.getId()).orElseThrow().getStatus());

        // 交接单详情含按数量/重量的锐器盒签收汇总
        Map<String, Object> detail = handoverService.detail(order.getOrderNo());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sharpResult = (List<Map<String, Object>>) detail.get("sharpContainers");
        assertEquals(1, sharpResult.size());
        assertEquals(10, sharpResult.get(0).get("receivedItems"));
        assertEquals(0.4, (double) sharpResult.get(0).get("receivedWeightKg"), 1e-9);

        // 批次追溯含针剂投放与锐器盒
        String batchNo = batchRepository.findById(bag1.getBatchId()).orElseThrow().getBatchNo();
        Map<String, Object> trace = batchService.trace(batchNo);
        assertEquals(1, ((List<?>) trace.get("sharpDeposits")).size());
        assertEquals(1, ((List<?>) trace.get("sharpContainers")).size());
    }

    @Test
    void damagedPackageCreatesAccidentAndRepackage() {
        Account resident = u("resident1");
        Account recycler = u("recycler1");
        Account reviewer = u("reviewer1");
        Account admin = u("admin1");
        Long siteId = siteRepository.findAll().get(0).getId();

        SharpContainer box = sharpService.registerContainer(siteId, "破损测试盒", 50, 5.0);
        DrugBag bag = weighedSyringeBag(resident, recycler, reviewer, siteId, 0.5, 8, true, false, "photo://x");
        sharpService.deposit(recycler.getId(), bag.getBagNo(),
                new SharpService.DepositRequest(box.getContainerNo(), 8, true, false, "photo://x"));
        SharpContainer sealed = sharpService.seal(recycler.getId(), box.getContainerNo(), "photo://label");

        HandoverOrder order = handoverService.create(siteId, "绿源环保科技", "市危废处理中心",
                List.of(), List.of(sealed.getContainerNo()), true);
        handoverService.sign(recycler.getId(), order.getOrderNo());
        handoverService.sign(admin.getId(), order.getOrderNo());

        // 签收时发现包装破损
        HandoverOrder done = handoverService.complete(order.getOrderNo(), "绿源-张司机", "photo://dmg",
                List.of(new HandoverService.ItemResult(sealed.getHandoffLabelNo(), false, null,
                        null, null, true, "盒盖破裂有针刺穿出风险")));
        assertEquals(HandoverStatus.PARTIAL_REJECTED, done.getStatus());

        // 回收点补充事故记录（PACKAGE_DAMAGED），锐器盒停用，针剂袋退回等待重新封装
        boolean accident = exceptionRepository.findAll().stream()
                .anyMatch(x -> x.getType() == ExceptionType.PACKAGE_DAMAGED
                        && box.getContainerNo().equals(x.getSharpContainerNo()));
        assertTrue(accident, "包装破损须补充事故记录");
        assertEquals(SharpContainerStatus.DISABLED, containerRepository.findById(box.getId()).orElseThrow().getStatus());
        assertEquals(BagStatus.ACCEPTED, bagRepository.findById(bag.getId()).orElseThrow().getStatus());

        // 重新封装：转入新锐器盒，再次封箱交接成功
        SharpContainer box2 = sharpService.registerContainer(siteId, "重新封装盒", 50, 5.0);
        sharpService.deposit(recycler.getId(), bag.getBagNo(),
                new SharpService.DepositRequest(box2.getContainerNo(), 8, true, false, "photo://repack"));
        SharpContainer sealed2 = sharpService.seal(recycler.getId(), box2.getContainerNo(), "photo://label2");
        HandoverOrder order2 = handoverService.create(siteId, "绿源环保科技", "市危废处理中心",
                List.of(), List.of(sealed2.getContainerNo()), true);
        handoverService.sign(recycler.getId(), order2.getOrderNo());
        handoverService.sign(admin.getId(), order2.getOrderNo());
        HandoverOrder done2 = handoverService.complete(order2.getOrderNo(), "绿源-张司机", "photo://ok",
                List.of(new HandoverService.ItemResult(sealed2.getHandoffLabelNo(), true, null,
                        8, 0.5, false, null)));
        assertEquals(HandoverStatus.COMPLETED, done2.getStatus());
        assertEquals(SharpContainerStatus.HANDED_OVER,
                containerRepository.findById(box2.getId()).orElseThrow().getStatus());
    }
}
