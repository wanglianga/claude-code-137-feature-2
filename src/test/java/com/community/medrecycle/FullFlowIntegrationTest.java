package com.community.medrecycle;

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

/** 主链路：预约 -> 登记 -> 称重 -> 复核 -> 积分 -> 暂存 -> 双人交接 -> 完成 -> 追溯 */
@SpringBootTest
class FullFlowIntegrationTest {

    @Autowired
    AppointmentService appointmentService;
    @Autowired
    BatchService batchService;
    @Autowired
    ReviewService reviewService;
    @Autowired
    StorageService storageService;
    @Autowired
    HandoverService handoverService;
    @Autowired
    PointsService pointsService;
    @Autowired
    AccountRepository accountRepository;
    @Autowired
    CommunitySiteRepository siteRepository;
    @Autowired
    StorageCabinetRepository cabinetRepository;
    @Autowired
    DrugBagRepository bagRepository;
    @Autowired
    AppointmentRepository appointmentRepository;

    @Test
    void fullRecycleFlow() {
        Account resident = accountRepository.findByUsername("resident1").orElseThrow();
        Account recycler = accountRepository.findByUsername("recycler1").orElseThrow();
        Account reviewer = accountRepository.findByUsername("reviewer1").orElseThrow();
        Account admin = accountRepository.findByUsername("admin1").orElseThrow();
        CommunitySite site = siteRepository.findAll().get(0);
        StorageCabinet cabinet = cabinetRepository.findBySiteId(site.getId()).get(0);

        int balanceBefore = pointsService.balanceOf(resident.getId());

        // 1. 居民预约（含处方药、液体、个人信息包装标识，记录积分规则快照）
        Appointment appointment = appointmentService.create(resident.getId(), site.getId(),
                "感冒药、降压药、安定片", 6, true, true, false, true, null);
        assertEquals(AppointmentStatus.PENDING, appointment.getStatus());
        assertTrue(appointment.getPointsRuleSnapshot().contains("TABLET:10"));

        // 2. 回收员扫码登记
        RecycleBatch batch = batchService.checkIn(recycler.getId(), appointment.getAppointmentNo());
        assertEquals(BatchStatus.CHECKED_IN, batch.getStatus());

        // 3. 分类称重：普通片剂 2kg（直接验收）+ 精神类 0.5kg（高风险进复核）
        batch = batchService.weigh(recycler.getId(), batch.getBatchNo(), List.of(
                new BatchService.WeighItem(DrugCategory.TABLET, 2.0, "SEAL-T-001", "photo://pkg1,photo://bag1", false),
                new BatchService.WeighItem(DrugCategory.PSYCHOTROPIC, 0.5, "SEAL-P-001", "photo://pkg2,photo://bag2", false)));
        batch = batchService.finishWeighing(batch.getBatchNo());
        assertEquals(BatchStatus.IN_REVIEW, batch.getStatus());

        // 普通片剂即时发放：2.0 * 10 = 20 分
        assertEquals(balanceBefore + 20, pointsService.balanceOf(resident.getId()));

        // 4. 人工复核精神类药品，通过后发放 0.5 * 20 = 10 分
        List<ReviewTask> pending = reviewService.list(ReviewStatus.PENDING);
        assertEquals(1, pending.size());
        reviewService.decide(reviewer.getId(), pending.get(0).getTaskNo(), true, "核对无误");
        assertEquals(balanceBefore + 30, pointsService.balanceOf(resident.getId()));

        batch = batchService.get(batch.getBatchNo());
        assertEquals(BatchStatus.POINTS_GRANTED, batch.getStatus());
        assertEquals(30, batch.getTotalPoints());

        // 5. 入暂存柜
        List<String> bagNos = bagRepository.findByBatchId(batch.getId()).stream().map(DrugBag::getBagNo).toList();
        assertEquals(2, bagNos.size());
        Map<String, Object> putResult = storageService.putBags(cabinet.getId(), bagNos);
        assertEquals(2, putResult.get("storedBags"));
        assertEquals(BatchStatus.STORED, batchService.get(batch.getBatchNo()).getStatus());

        // 6. 发起环保交接：重量不足上门量，force 强制；含高风险袋 -> 需要双人交接
        HandoverOrder order = handoverService.create(site.getId(), "绿源环保科技", "市危废处理中心",
                bagNos, null, true);
        assertTrue(order.isRequiresDual());
        assertEquals(HandoverStatus.DRAFT, order.getStatus());

        // 单人签收不够，双人签收后才出库
        HandoverOrder afterFirstSign = handoverService.sign(recycler.getId(), order.getOrderNo());
        assertEquals(HandoverStatus.DRAFT, afterFirstSign.getStatus());
        HandoverOrder afterSecondSign = handoverService.sign(admin.getId(), order.getOrderNo());
        assertEquals(HandoverStatus.IN_TRANSIT, afterSecondSign.getStatus());

        // 7. 环保公司签收完成
        HandoverOrder completed = handoverService.complete(order.getOrderNo(), "绿源-张司机",
                "photo://handover1", null);
        assertEquals(HandoverStatus.COMPLETED, completed.getStatus());
        assertEquals("绿源-张司机", completed.getReceiverName());

        // 批次与预约进入终态
        assertEquals(BatchStatus.HANDED_OVER, batchService.get(batch.getBatchNo()).getStatus());
        assertEquals(AppointmentStatus.COMPLETED,
                appointmentRepository.findById(appointment.getId()).orElseThrow().getStatus());

        // 8. 全链路追溯
        Map<String, Object> trace = batchService.trace(batch.getBatchNo());
        assertNotNull(trace.get("appointment"));
        assertEquals(2, ((List<?>) trace.get("weighRecords")).size());
        List<?> traceBags = (List<?>) trace.get("bags");
        assertEquals(2, traceBags.size());
        Map<?, ?> psychoBag = (Map<?, ?>) traceBags.stream()
                .filter(b -> ((DrugBag) ((Map<?, ?>) b).get("bag")).getCategory() == DrugCategory.PSYCHOTROPIC)
                .findFirst().orElseThrow();
        assertEquals(1, ((List<?>) psychoBag.get("reviewTasks")).size());
        assertEquals(1, ((List<?>) psychoBag.get("handovers")).size());
        assertFalse(((List<?>) psychoBag.get("pointsTransactions")).isEmpty());

        // 交接单详情含积分发放结果
        Map<String, Object> detail = handoverService.detail(order.getOrderNo());
        assertFalse(((List<?>) detail.get("pointsResult")).isEmpty());
    }
}
