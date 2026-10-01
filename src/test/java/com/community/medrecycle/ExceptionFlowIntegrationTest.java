package com.community.medrecycle;

import com.community.medrecycle.domain.*;
import com.community.medrecycle.domain.enums.*;
import com.community.medrecycle.repository.*;
import com.community.medrecycle.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 异常链路：夹带未过期药扣回、称重争议复秤、环保公司拒收 */
@SpringBootTest
class ExceptionFlowIntegrationTest {

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
    ExceptionService exceptionService;
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
    ReviewTaskRepository reviewTaskRepository;

    private RecycleBatch weighedBatch(Account resident, Account recycler, Long siteId,
                                      DrugCategory category, double weightKg, boolean dispute) {
        Appointment appointment = appointmentService.create(resident.getId(), siteId,
                "测试药品", 2, false, false, false, false, null);
        RecycleBatch batch = batchService.checkIn(recycler.getId(), appointment.getAppointmentNo());
        return batchService.weigh(recycler.getId(), batch.getBatchNo(), List.of(
                new BatchService.WeighItem(category, weightKg, null, "photo://x", dispute)));
    }

    @Test
    void nonExpiredDrugRevokesPointsAndBlocksFlow() {
        Account resident = accountRepository.findByUsername("resident1").orElseThrow();
        Account recycler = accountRepository.findByUsername("recycler1").orElseThrow();
        Account reviewer = accountRepository.findByUsername("reviewer1").orElseThrow();
        Long siteId = siteRepository.findAll().get(0).getId();

        int before = pointsService.balanceOf(resident.getId());
        RecycleBatch batch = weighedBatch(resident, recycler, siteId, DrugCategory.TABLET, 3.0, false);
        assertEquals(before + 30, pointsService.balanceOf(resident.getId()));

        DrugBag bag = bagRepository.findByBatchId(batch.getId()).get(0);
        // 发现夹带未过期药：扣回积分并退回人工复核
        exceptionService.raise(batch.getBatchNo(), bag.getBagNo(),
                ExceptionType.NON_EXPIRED_DRUG, "其中一盒感冒药未过期");
        assertEquals(before, pointsService.balanceOf(resident.getId()));
        assertEquals(BagStatus.PENDING_REVIEW, bagRepository.findById(bag.getId()).orElseThrow().getStatus());
        assertEquals(BatchStatus.IN_REVIEW, batchService.get(batch.getBatchNo()).getStatus());

        // 复核拒绝 -> 袋终止流转，批次关闭
        ReviewTask task = reviewTaskRepository.findByBagIdAndStatus(bag.getId(), ReviewStatus.PENDING).get(0);
        reviewService.decide(reviewer.getId(), task.getTaskNo(), false, "未过期药品退回居民");
        assertEquals(BagStatus.REVIEW_REJECTED, bagRepository.findById(bag.getId()).orElseThrow().getStatus());
        assertEquals(BatchStatus.CLOSED, batchService.get(batch.getBatchNo()).getStatus());
    }

    @Test
    void weightDisputeReweighAdjustsPoints() {
        Account resident = accountRepository.findByUsername("resident1").orElseThrow();
        Account recycler = accountRepository.findByUsername("recycler1").orElseThrow();
        Long siteId = siteRepository.findAll().get(0).getId();

        int before = pointsService.balanceOf(resident.getId());
        // 称重时标记争议 -> 自动生成称重争议异常
        RecycleBatch batch = weighedBatch(resident, recycler, siteId, DrugCategory.ANTIBIOTIC, 2.0, true);
        assertEquals(before + 30, pointsService.balanceOf(resident.getId())); // 2.0 * 15

        List<ExceptionRecord> disputes = exceptionService.list(ExceptionStatus.OPEN).stream()
                .filter(e -> e.getType() == ExceptionType.WEIGHT_DISPUTE && e.getBatchNo().equals(batch.getBatchNo()))
                .toList();
        assertEquals(1, disputes.size());

        // 复秤 2.0 -> 2.6kg，积分 30 -> 39，补发 9 分，异常办结
        ExceptionRecord resolved = exceptionService.reweigh(
                recycler.getId(), disputes.get(0).getExceptionNo(), 2.6);
        assertEquals(ExceptionStatus.RESOLVED, resolved.getStatus());
        assertEquals(before + 39, pointsService.balanceOf(resident.getId()));
        assertEquals(2.6, bagRepository.findById(resolved.getBagId()).orElseThrow().getWeightKg(), 0.001);
    }

    @Test
    void companyRejectReturnsBagAndRaisesException() {
        Account resident = accountRepository.findByUsername("resident1").orElseThrow();
        Account recycler = accountRepository.findByUsername("recycler1").orElseThrow();
        Account admin = accountRepository.findByUsername("admin1").orElseThrow();
        Long siteId = siteRepository.findAll().get(0).getId();
        StorageCabinet cabinet = cabinetRepository.findBySiteId(siteId).get(0);

        RecycleBatch batch = weighedBatch(resident, recycler, siteId, DrugCategory.EXTERNAL, 1.0, false);
        DrugBag bag = bagRepository.findByBatchId(batch.getId()).get(0);
        storageService.putBags(cabinet.getId(), List.of(bag.getBagNo()));

        HandoverOrder order = handoverService.create(siteId, "绿源环保科技", "市危废处理中心",
                List.of(bag.getBagNo()), null, true);
        handoverService.sign(recycler.getId(), order.getOrderNo());
        // 环保公司拒收外用药袋
        HandoverOrder completed = handoverService.complete(order.getOrderNo(), "绿源-张司机", null,
                List.of(new HandoverService.ItemResult(bag.getBagNo(), false, "外用药含管状包装不在接收范围")));

        assertEquals(HandoverStatus.PARTIAL_REJECTED, completed.getStatus());
        // 袋退回暂存，异常已记录
        assertEquals(BagStatus.STORED, bagRepository.findById(bag.getId()).orElseThrow().getStatus());
        List<ExceptionRecord> rejects = exceptionService.list(null).stream()
                .filter(e -> e.getType() == ExceptionType.COMPANY_REJECT && e.getBagNo().equals(bag.getBagNo()))
                .toList();
        assertEquals(1, rejects.size());
    }
}
