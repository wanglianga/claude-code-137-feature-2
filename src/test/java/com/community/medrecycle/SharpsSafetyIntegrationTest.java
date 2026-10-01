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
 * 针剂安全封装复核：
 * 称重四要素校验 -> 未封装异常+复核 -> 复核通过转入锐器盒(特殊标签) -> 普通柜拒收
 * -> 容量预警/满盒停收 -> 封盒专项交接 -> 公司按数量重量签收 -> 破损事故+重新封装。
 */
@SpringBootTest
class SharpsSafetyIntegrationTest {

    @Autowired AppointmentService appointmentService;
    @Autowired BatchService batchService;
    @Autowired ReviewService reviewService;
    @Autowired StorageService storageService;
    @Autowired HandoverService handoverService;
    @Autowired SharpsService sharpsService;
    @Autowired ExceptionService exceptionService;
    @Autowired AccountRepository accountRepository;
    @Autowired CommunitySiteRepository siteRepository;
    @Autowired StorageCabinetRepository cabinetRepository;
    @Autowired DrugBagRepository bagRepository;
    @Autowired ReviewTaskRepository reviewTaskRepository;
    @Autowired SharpsContainerRepository containerRepository;
    @Autowired SharpsNotificationRepository notificationRepository;
    @Autowired HandoverContainerRepository handoverContainerRepository;
    @Autowired ExceptionRecordRepository exceptionRepository;

    private Account user(String username) {
        return accountRepository.findByUsername(username).orElseThrow();
    }

    /** 称重一袋针剂，返回批次 */
    private RecycleBatch weighSyringe(Account resident, Account recycler, Long siteId,
                                      boolean punctureResistant, boolean mixed,
                                      int sharpsCount, double weightKg, String sealNo) {
        Appointment appointment = appointmentService.create(resident.getId(), siteId,
                "胰岛素针头和注射器", sharpsCount, false, false, true, false, null);
        RecycleBatch batch = batchService.checkIn(recycler.getId(), appointment.getAppointmentNo());
        return batchService.weigh(recycler.getId(), batch.getBatchNo(), List.of(
                new BatchService.WeighItem(DrugCategory.SYRINGE, weightKg, sealNo,
                        "photo://sharps-pkg,photo://sharps-bag", false,
                        punctureResistant, mixed, sharpsCount)));
    }

    private void approveReviewForBag(Account reviewer, Long bagId, String note) {
        List<ReviewTask> tasks = reviewTaskRepository.findByBagIdAndStatus(bagId, ReviewStatus.PENDING);
        assertEquals(1, tasks.size(), "针剂袋应有一条待复核任务");
        reviewService.decide(reviewer.getId(), tasks.get(0).getTaskNo(), true, note);
    }

    /** 正常针剂：称重->复核->入锐器盒->封盒->双人交接->公司按数量重量签收 */
    @Test
    void safeSyringeFlowThroughSharpsHandover() {
        Account resident = user("resident1");
        Account recycler = user("recycler1");
        Account reviewer = user("reviewer1");
        Account admin = user("admin1");
        Long siteId = siteRepository.findAll().get(0).getId();
        StorageCabinet cabinet = cabinetRepository.findBySiteId(siteId).get(0);

        int balanceBefore = 0; // 只比较差值
        RecycleBatch batch = weighSyringe(resident, recycler, siteId, true, false, 20, 0.5,
                "SEAL-SAFE-0001");
        DrugBag bag0 = bagRepository.findByBatchId(batch.getId()).get(0);
        assertEquals(BagStatus.PENDING_REVIEW, bag0.getStatus(), "针剂为高风险，先复核");
        assertEquals(20, bag0.getSharpsCount());
        assertTrue(bag0.isPunctureResistant());

        // 普通暂存柜在复核前后均不得接收针剂
        BizException beforeReview = assertThrows(BizException.class,
                () -> storageService.putBags(cabinet.getId(), List.of(bag0.getBagNo())));
        assertEquals(409, beforeReview.getStatus());

        approveReviewForBag(reviewer, bag0.getId(), "针剂已确认防刺封装，数量无误");
        DrugBag bag = bagRepository.findById(bag0.getId()).orElseThrow();
        assertEquals(BagStatus.ACCEPTED, bag.getStatus());
        int granted = bag.getPoints(); // 0.5 * 8 = 4
        assertEquals(4, granted);
        assertTrue(bag.isPointsGranted());

        // 复核通过后仍不能进普通柜，必须转锐器盒
        String bagNo = bag.getBagNo();
        BizException afterReview = assertThrows(BizException.class,
                () -> storageService.putBags(cabinet.getId(), List.of(bagNo)));
        assertEquals(409, afterReview.getStatus());
        assertTrue(afterReview.getMessage().contains("锐器盒"));

        SharpsContainer box = sharpsService.registerContainer(siteId, "测试-针剂安全盒-正常",
                100, 10.0, 0.8);
        SharpsService.DepositResult deposit = sharpsService.deposit(recycler.getId(),
                box.getContainerNo(), bagNo, "photo://deposit-1", null);
        assertTrue(deposit.labelNo().startsWith("SHL"), "首次转入即生成特殊交接标签");
        assertEquals(20, deposit.totalSharps());
        bag = bagRepository.findById(bag.getId()).orElseThrow();
        assertEquals(BagStatus.IN_SHARPS_BOX, bag.getStatus());
        assertEquals(deposit.labelNo(), bag.getSharpsLabelNo());

        // 转入记录与批次状态
        Map<String, Object> trace = batchService.trace(batch.getBatchNo());
        List<?> traceBags = (List<?>) trace.get("bags");
        Map<?, ?> syringeView = (Map<?, ?>) traceBags.get(0);
        assertEquals(1, ((List<?>) syringeView.get("sharpsDeposits")).size());
        assertEquals(BatchStatus.STORED, batchService.get(batch.getBatchNo()).getStatus(),
                "针剂入锐器盒即视为安全暂存");

        // 封盒 -> 锐器专项交接（含锐器盒自动要求双人）
        SharpsContainer sealed = sharpsService.seal(box.getContainerNo());
        assertEquals(SharpsStatus.SEALED, sealed.getStatus());
        assertEquals(deposit.labelNo(), sealed.getSpecialLabelNo());

        HandoverOrder order = handoverService.create(siteId, "绿源环保科技", "市危废处置中心",
                List.of(), List.of(sealed.getContainerNo()), true);
        assertEquals(1, order.getSharpsContainerCount());
        assertEquals(20, order.getSharpsCount());
        assertEquals(0.5, order.getSharpsWeightKg(), 0.001);
        assertEquals(0.5, order.getTotalWeightKg(), 0.001);
        assertTrue(order.isRequiresDual(), "含锐器盒必须双人交接");

        handoverService.sign(recycler.getId(), order.getOrderNo());
        handoverService.sign(admin.getId(), order.getOrderNo());

        // 环保公司按锐器数量与重量逐盒确认
        HandoverOrder done = handoverService.complete(order.getOrderNo(), "绿源-周签收",
                "photo://sharps-receive", List.of(),
                List.of(new HandoverService.ContainerResult(sealed.getContainerNo(), true,
                        20, 0.5, false, null)));
        assertEquals(HandoverStatus.COMPLETED, done.getStatus());
        assertEquals(SharpsStatus.HANDED_OVER,
                containerRepository.findById(box.getId()).orElseThrow().getStatus());
        final Long bagId = bag.getId();
        assertEquals(BagStatus.HANDED_OVER, bagRepository.findById(bagId).orElseThrow().getStatus());

        HandoverContainer hc = handoverContainerRepository.findByOrderId(order.getId()).get(0);
        assertEquals(20, hc.getReceivedSharpsCount());
        assertEquals(0.5, hc.getReceivedWeightKg(), 0.001);
        assertEquals(deposit.labelNo(), hc.getLabelNo());

        Map<String, Object> detail = handoverService.detail(order.getOrderNo());
        assertEquals(1, ((List<?>) detail.get("sharpsContainers")).size());
        assertEquals(1, ((List<?>) detail.get("pointsResult")).size());
    }

    /** 未封装针剂：称重触发 UNSAFE_INJECTION 异常与复核，复核处置后转入锐器盒 */
    @Test
    void unsafeSyringeRaisesExceptionAndRequiresRepackIntoBox() {
        Account resident = user("resident2");
        Account recycler = user("recycler1");
        Account reviewer = user("reviewer1");
        Long siteId = siteRepository.findAll().get(0).getId();

        RecycleBatch batch = weighSyringe(resident, recycler, siteId, false, false, 10, 0.2,
                "SEAL-UNSAFE-0001");
        DrugBag bag = bagRepository.findByBatchId(batch.getId()).get(0);
        assertEquals(BagStatus.PENDING_REVIEW, bag.getStatus());

        // 自动生成针剂未封装异常，复核原因也是 UNSAFE_INJECTION
        List<ExceptionRecord> unsafe = exceptionRepository.findByBatchId(batch.getId()).stream()
                .filter(e -> e.getType() == ExceptionType.UNSAFE_INJECTION).toList();
        assertEquals(1, unsafe.size());
        ReviewTask task = reviewTaskRepository.findByBagIdAndStatus(bag.getId(), ReviewStatus.PENDING).get(0);
        assertEquals("UNSAFE_INJECTION", task.getReason());
        assertFalse(bag.isPointsGranted(), "未复核通过不发积分");

        approveReviewForBag(reviewer, bag.getId(), "现场已将散针转入防刺锐器盒");
        bag = bagRepository.findById(bag.getId()).orElseThrow();
        assertEquals(BagStatus.ACCEPTED, bag.getStatus());
        assertTrue(bag.isPointsGranted());

        SharpsContainer box = sharpsService.registerContainer(siteId, "测试-针剂安全盒-补封",
                100, 10.0, 0.8);
        SharpsService.DepositResult r = sharpsService.deposit(recycler.getId(),
                box.getContainerNo(), bag.getBagNo(), "photo://repack-on-site", "居民散针现场补封");
        assertTrue(r.labelNo().startsWith("SHL"));
        assertEquals(BagStatus.IN_SHARPS_BOX, bagRepository.findById(bag.getId()).orElseThrow().getStatus());
    }

    /** 称重四要素强校验：缺数量、缺照片均不可登记；混入普通药袋触发未封装异常 */
    @Test
    void syringeWeighRequiresSafetyConfirmations() {
        Account resident = user("resident1");
        Account recycler = user("recycler1");
        Long siteId = siteRepository.findAll().get(0).getId();

        // 未登记锐器数量 -> 400
        Appointment a1 = appointmentService.create(resident.getId(), siteId, "注射器", 5,
                false, false, true, false, null);
        RecycleBatch b1 = batchService.checkIn(recycler.getId(), a1.getAppointmentNo());
        BizException noCount = assertThrows(BizException.class, () -> batchService.weigh(
                recycler.getId(), b1.getBatchNo(), List.of(new BatchService.WeighItem(
                        DrugCategory.SYRINGE, 0.3, null, "photo://x", false, true, false, 0))));
        assertEquals(400, noCount.getStatus());

        // 未上传封装照片 -> 400
        Appointment a2 = appointmentService.create(resident.getId(), siteId, "注射器", 5,
                false, false, true, false, null);
        RecycleBatch b2 = batchService.checkIn(recycler.getId(), a2.getAppointmentNo());
        BizException noPhoto = assertThrows(BizException.class, () -> batchService.weigh(
                recycler.getId(), b2.getBatchNo(), List.of(new BatchService.WeighItem(
                        DrugCategory.SYRINGE, 0.3, null, null, false, true, false, 5))));
        assertEquals(400, noPhoto.getStatus());

        // 已用防刺容器但混入普通药袋 -> 针剂未封装异常，强制复核
        RecycleBatch b3 = weighSyringe(resident, recycler, siteId, true, true, 8, 0.2,
                "SEAL-MIX-0001");
        DrugBag mixedBag = bagRepository.findByBatchId(b3.getId()).get(0);
        assertEquals(BagStatus.PENDING_REVIEW, mixedBag.getStatus());
        assertTrue(mixedBag.isMixedWithNormalBags());
        boolean hasUnsafe = exceptionRepository.findByBatchId(b3.getId()).stream()
                .anyMatch(e -> e.getType() == ExceptionType.UNSAFE_INJECTION);
        assertTrue(hasUnsafe, "针剂混入普通药袋必须生成未封装异常");
    }

    /** 容量预警：达 80% 提前通知；达上限拒绝接收并通知停收，禁止现场继续收 */
    @Test
    void sharpsBoxCapacityWarnsThenRejectsFullBox() {
        Account resident = user("resident1");
        Account recycler = user("recycler1");
        Account reviewer = user("reviewer1");
        Long siteId = siteRepository.findAll().get(0).getId();

        // 容量 10 支：9 支即 90%，触发 NEAR_FULL；再进 5 支必然超限
        SharpsContainer box = sharpsService.registerContainer(siteId, "测试-针剂小容量盒",
                10, 10.0, 0.8);

        RecycleBatch b1 = weighSyringe(resident, recycler, siteId, true, false, 9, 0.3,
                "SEAL-CAP-0001");
        DrugBag bag1 = bagRepository.findByBatchId(b1.getId()).get(0);
        approveReviewForBag(reviewer, bag1.getId(), "ok");
        SharpsService.DepositResult r1 = sharpsService.deposit(recycler.getId(),
                box.getContainerNo(), bag1.getBagNo(), "photo://cap-1", null);
        assertEquals("WARN", r1.warning());
        assertTrue(notificationRepository.existsByContainerIdAndType(
                box.getId(), SharpsNotifyType.NEAR_FULL), "达预警线提前通知环保公司/上级点");

        RecycleBatch b2 = weighSyringe(resident, recycler, siteId, true, false, 5, 0.2,
                "SEAL-CAP-0002");
        DrugBag bag2 = bagRepository.findByBatchId(b2.getId()).get(0);
        approveReviewForBag(reviewer, bag2.getId(), "ok");
        BizException full = assertThrows(BizException.class, () -> sharpsService.deposit(
                recycler.getId(), box.getContainerNo(), bag2.getBagNo(), "photo://cap-2", null));
        assertEquals(409, full.getStatus());
        assertTrue(full.getMessage().contains("容量上限"));
        assertTrue(notificationRepository.existsByContainerIdAndType(
                box.getId(), SharpsNotifyType.FULL), "满盒拒绝接收时生成停收通知");

        // 被拒针剂仍是 ACCEPTED，可转入新锐器盒，不会无处安放
        SharpsContainer box2 = sharpsService.registerContainer(siteId, "测试-针剂备用盒",
                100, 10.0, 0.8);
        sharpsService.deposit(recycler.getId(), box2.getContainerNo(), bag2.getBagNo(),
                "photo://cap-2-ok", null);
        assertEquals(BagStatus.IN_SHARPS_BOX, bagRepository.findById(bag2.getId()).orElseThrow().getStatus());
    }

    /** 签收发现包装破损：整盒退回冻结、按批次补事故记录；重新封装到新盒（新标签）后可再交接 */
    @Test
    void damagedPackagingRequiresIncidentAndRepackaging() {
        Account resident = user("resident2");
        Account recycler = user("recycler1");
        Account reviewer = user("reviewer1");
        Account admin = user("admin1");
        Long siteId = siteRepository.findAll().get(0).getId();

        RecycleBatch batch = weighSyringe(resident, recycler, siteId, true, false, 15, 0.4,
                "SEAL-DMG-0001");
        DrugBag bag = bagRepository.findByBatchId(batch.getId()).get(0);
        approveReviewForBag(reviewer, bag.getId(), "ok");

        SharpsContainer box = sharpsService.registerContainer(siteId, "测试-针剂破损盒",
                100, 10.0, 0.8);
        SharpsService.DepositResult deposit = sharpsService.deposit(recycler.getId(),
                box.getContainerNo(), bag.getBagNo(), "photo://dmg-deposit", null);
        sharpsService.seal(box.getContainerNo());

        HandoverOrder order = handoverService.create(siteId, "绿源环保科技", "市危废处置中心",
                List.of(), List.of(box.getContainerNo()), true);
        handoverService.sign(recycler.getId(), order.getOrderNo());
        handoverService.sign(admin.getId(), order.getOrderNo());

        HandoverOrder done = handoverService.complete(order.getOrderNo(), "绿源-周签收",
                "photo://dmg-found", List.of(),
                List.of(new HandoverService.ContainerResult(box.getContainerNo(), false,
                        null, null, true, "盒体开裂有针刺穿风险")));
        assertEquals(HandoverStatus.PARTIAL_REJECTED, done.getStatus());

        SharpsContainer damaged = containerRepository.findById(box.getId()).orElseThrow();
        assertTrue(damaged.isPackagingDamaged(), "破损盒冻结等待重新封装");
        assertEquals(SharpsStatus.SEALED, damaged.getStatus());
        assertNull(damaged.getAssignedOrderId());
        assertEquals(BagStatus.IN_SHARPS_BOX, bagRepository.findById(bag.getId()).orElseThrow().getStatus(),
                "重新封装完成前针剂仍挂在盒内，不进入普通流转");

        // 破损事故记录已补充
        List<ExceptionRecord> incidents = exceptionRepository.findByBatchId(batch.getId()).stream()
                .filter(e -> e.getType() == ExceptionType.SHARPS_PACKAGING_DAMAGED).toList();
        assertFalse(incidents.isEmpty());

        // 破损盒不得再次建单
        BizException rejected = assertThrows(BizException.class, () -> handoverService.create(
                siteId, "绿源环保科技", "市危废处置中心",
                List.of(), List.of(box.getContainerNo()), true));
        assertEquals(409, rejected.getStatus());

        // 重新封装到全新空盒：新标签、旧盒报废、事故单关联新盒
        SharpsContainer fresh = sharpsService.registerContainer(siteId, "测试-针剂重新封装盒",
                100, 10.0, 0.8);
        SharpsService.RepackResult repack = sharpsService.repackage(recycler.getId(),
                box.getContainerNo(), fresh.getContainerNo(), "photo://repack-done", "更换新防刺盒");
        assertEquals(deposit.labelNo(), repack.newLabelNo() == null ? null : deposit.labelNo());
        assertNotEquals(deposit.labelNo(), repack.newLabelNo(), "重新封装必须生成新特殊标签");
        assertTrue(repack.newLabelNo().startsWith("SHL"));
        assertEquals(15, repack.movedSharps());
        assertEquals(0.4, repack.movedWeightKg(), 0.001);
        assertEquals(SharpsStatus.DISPOSED, containerRepository.findById(box.getId()).orElseThrow().getStatus());
        SharpsContainer newBox = containerRepository.findById(fresh.getId()).orElseThrow();
        assertEquals(SharpsStatus.SEALED, newBox.getStatus());
        assertEquals(repack.newLabelNo(), newBox.getSpecialLabelNo());
        DrugBag movedBag = bagRepository.findById(bag.getId()).orElseThrow();
        assertEquals(newBox.getId(), movedBag.getSharpsContainerId());
        assertEquals(repack.newLabelNo(), movedBag.getSharpsLabelNo());
        assertFalse(repack.incidentNos().isEmpty(), "重新封装补充事故记录");

        // 新盒可正常再次交接并被公司签收
        HandoverOrder order2 = handoverService.create(siteId, "绿源环保科技", "市危废处置中心",
                List.of(), List.of(newBox.getContainerNo()), true);
        handoverService.sign(recycler.getId(), order2.getOrderNo());
        handoverService.sign(admin.getId(), order2.getOrderNo());
        HandoverOrder done2 = handoverService.complete(order2.getOrderNo(), "绿源-周签收",
                "photo://repack-receive", List.of(),
                List.of(new HandoverService.ContainerResult(newBox.getContainerNo(), true,
                        15, 0.4, false, null)));
        assertEquals(HandoverStatus.COMPLETED, done2.getStatus());
        assertEquals(SharpsStatus.HANDED_OVER,
                containerRepository.findById(fresh.getId()).orElseThrow().getStatus());
        assertEquals(BagStatus.HANDED_OVER, bagRepository.findById(bag.getId()).orElseThrow().getStatus());
    }
}
