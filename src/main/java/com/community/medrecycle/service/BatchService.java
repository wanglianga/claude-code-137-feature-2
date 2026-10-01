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

/** 回收批次：扫码登记、分类称重封袋、全链路追溯 */
@Service
@RequiredArgsConstructor
public class BatchService {

    private final RecycleBatchRepository batchRepository;
    private final AppointmentRepository appointmentRepository;
    private final ActivityDayRepository activityDayRepository;
    private final WeighRecordRepository weighRecordRepository;
    private final DrugBagRepository bagRepository;
    private final ReviewTaskRepository reviewTaskRepository;
    private final StorageEntryRepository storageEntryRepository;
    private final HandoverItemRepository handoverItemRepository;
    private final HandoverOrderRepository handoverOrderRepository;
    private final ExceptionRecordRepository exceptionRecordRepository;
    private final PointsTransactionRepository pointsTransactionRepository;
    private final SharpDepositRepository sharpDepositRepository;
    private final SharpContainerRepository sharpContainerRepository;
    private final PointsRuleService pointsRuleService;
    private final PointsService pointsService;
    private final BatchRefresher batchRefresher;

    /** 回收员现场扫码登记：预约 -> 批次 */
    @Transactional
    public RecycleBatch checkIn(Long recyclerId, String appointmentNo) {
        Appointment appointment = appointmentRepository.findByAppointmentNo(appointmentNo)
                .orElseThrow(() -> BizException.notFound("预约不存在: " + appointmentNo));
        if (appointment.getStatus() != AppointmentStatus.PENDING) {
            throw BizException.conflict("预约状态不可登记: " + appointment.getStatus());
        }

        double multiplier = 1.0;
        if (appointment.getActivityDayId() != null) {
            multiplier = activityDayRepository.findById(appointment.getActivityDayId())
                    .map(ActivityDay::getPointsMultiplier).orElse(1.0);
        }

        RecycleBatch batch = new RecycleBatch();
        batch.setBatchNo(NoGen.next("BN"));
        batch.setAppointmentId(appointment.getId());
        batch.setAppointmentNo(appointment.getAppointmentNo());
        batch.setResidentId(appointment.getResidentId());
        batch.setSiteId(appointment.getSiteId());
        batch.setRecyclerId(recyclerId);
        batch.setActivityDayId(appointment.getActivityDayId());
        batch.setPointsMultiplier(multiplier);
        batch.setStatus(BatchStatus.CHECKED_IN);
        RecycleBatch saved = batchRepository.save(batch);

        appointment.setStatus(AppointmentStatus.CHECKED_IN);
        appointment.setCheckedInAt(LocalDateTime.now());
        appointmentRepository.save(appointment);
        return saved;
    }

    public record WeighItem(DrugCategory category, double weightKg, String sealNo,
                            String photoUrls, boolean dispute,
                            boolean punctureProofContainer, boolean mixedWithRegularBag,
                            int sharpItemCount) {

        /** 非针剂品类的便捷构造（兼容既有调用与测试） */
        public WeighItem(DrugCategory category, double weightKg, String sealNo,
                         String photoUrls, boolean dispute) {
            this(category, weightKg, sealNo, photoUrls, dispute, false, false, 0);
        }
    }

    /** 现场分类称重封袋：生成称重记录与封袋，高风险品类自动进入人工复核 */
    @Transactional
    public RecycleBatch weigh(Long operatorId, String batchNo, List<WeighItem> items) {
        RecycleBatch batch = batchRepository.findByBatchNo(batchNo)
                .orElseThrow(() -> BizException.notFound("批次不存在: " + batchNo));
        if (batch.getStatus() != BatchStatus.CHECKED_IN && batch.getStatus() != BatchStatus.WEIGHING
                && batch.getStatus() != BatchStatus.IN_REVIEW) {
            throw BizException.conflict("当前批次状态不可称重: " + batch.getStatus());
        }
        if (items == null || items.isEmpty()) {
            throw BizException.badRequest("称重记录不能为空");
        }
        Appointment appointment = appointmentRepository.findById(batch.getAppointmentId()).orElseThrow();

        for (WeighItem item : items) {
            if (item.category() == null) {
                throw BizException.badRequest("药品分类不能为空");
            }
            if (item.weightKg() <= 0) {
                throw BizException.badRequest("称重必须大于 0");
            }
            boolean isSyringe = item.category() == DrugCategory.SYRINGE;
            // 针剂安全封装复核：胰岛素针头/注射器必须确认数量与现场照片
            if (isSyringe) {
                if (item.sharpItemCount() <= 0) {
                    throw BizException.badRequest("针剂称重必须登记锐器数量（支）");
                }
                if (item.photoUrls() == null || item.photoUrls().isBlank()) {
                    throw BizException.badRequest("针剂称重必须提供封装与称重照片");
                }
            }

            WeighRecord record = new WeighRecord();
            record.setBatchId(batch.getId());
            record.setCategory(item.category());
            record.setWeightKg(item.weightKg());
            record.setPhotoUrls(item.photoUrls());
            record.setDispute(item.dispute());
            record.setPunctureProofContainer(item.punctureProofContainer());
            record.setMixedWithRegularBag(item.mixedWithRegularBag());
            record.setSharpItemCount(item.sharpItemCount());
            record.setWeighedBy(operatorId);
            weighRecordRepository.save(record);

            DrugBag bag = new DrugBag();
            bag.setBagNo(NoGen.next("BAG"));
            bag.setBatchId(batch.getId());
            bag.setSiteId(batch.getSiteId());
            bag.setCategory(item.category());
            bag.setWeightKg(item.weightKg());
            String sealNo = (item.sealNo() == null || item.sealNo().isBlank())
                    ? NoGen.next("SEAL") : item.sealNo();
            if (bagRepository.findBySealNo(sealNo).isPresent()) {
                throw BizException.conflict("封袋编号重复: " + sealNo);
            }
            bag.setSealNo(sealNo);
            bag.setPhotoUrls(item.photoUrls());
            boolean highRisk = pointsRuleService.isHighRisk(item.category());
            bag.setHighRisk(highRisk);
            bag.setPoints(pointsRuleService.pointsFor(
                    appointment.getPointsRuleSnapshot(), item.category(), item.weightKg(), batch.getPointsMultiplier()));

            if (highRisk) {
                // 高风险药品：先人工复核，复核通过才发积分
                bag.setStatus(BagStatus.PENDING_REVIEW);
                bagRepository.save(bag);
                record.setBagId(bag.getId());
                weighRecordRepository.save(record);
                createReviewTask(batch, bag, "HIGH_RISK_CATEGORY");
            } else {
                bag.setStatus(BagStatus.ACCEPTED);
                bagRepository.save(bag);
                record.setBagId(bag.getId());
                weighRecordRepository.save(record);
                grantBagPoints(batch, bag);
            }

            // 针剂安全封装复核：现场确认未使用防刺容器或混入普通药袋，立即生成针剂未封装异常
            // 未封装针剂不能进入普通暂存柜（高风险袋已在复核队列，复核通过后仍须转入锐器盒）
            if (isSyringe && (!item.punctureProofContainer() || item.mixedWithRegularBag())) {
                raiseAutoException(batch, bag, ExceptionType.UNSAFE_INJECTION,
                        "针剂安全封装复核未通过："
                                + (!item.punctureProofContainer() ? "未使用防刺容器；" : "")
                                + (item.mixedWithRegularBag() ? "混入普通药袋；" : "")
                                + "数量 " + item.sharpItemCount()
                                + " 支，重量 " + item.weightKg() + "kg。须现场重新规范封装后转入锐器盒");
            }

            if (item.dispute()) {
                raiseAutoException(batch, bag, ExceptionType.WEIGHT_DISPUTE,
                        "现场称重存在争议，品类 " + item.category() + "，登记重量 " + item.weightKg() + "kg");
            }
        }

        batch.setStatus(BatchStatus.WEIGHING);
        batchRepository.save(batch);
        return batchRefresher.refresh(batch.getId());
    }

    /** 称重完成：有待复核袋 -> IN_REVIEW，否则 -> POINTS_GRANTED */
    @Transactional
    public RecycleBatch finishWeighing(String batchNo) {
        RecycleBatch batch = batchRepository.findByBatchNo(batchNo)
                .orElseThrow(() -> BizException.notFound("批次不存在: " + batchNo));
        if (bagRepository.findByBatchId(batch.getId()).isEmpty()) {
            throw BizException.conflict("批次尚无称重封袋记录");
        }
        return batchRefresher.refresh(batch.getId());
    }

    void createReviewTask(RecycleBatch batch, DrugBag bag, String reason) {
        ReviewTask task = new ReviewTask();
        task.setTaskNo(NoGen.next("RT"));
        task.setBatchId(batch.getId());
        task.setBagId(bag.getId());
        task.setBagNo(bag.getBagNo());
        task.setReason(reason);
        reviewTaskRepository.save(task);
    }

    /** 发放单袋积分（幂等：已发放则跳过） */
    void grantBagPoints(RecycleBatch batch, DrugBag bag) {
        if (bag.isPointsGranted() || bag.getPoints() <= 0) {
            return;
        }
        pointsService.grant(batch.getResidentId(), bag.getPoints(), "BAG", bag.getBagNo(),
                "回收发放 " + bag.getCategory() + " " + bag.getWeightKg() + "kg");
        bag.setPointsGranted(true);
        bagRepository.save(bag);
    }

    void raiseAutoException(RecycleBatch batch, DrugBag bag, ExceptionType type, String description) {
        ExceptionRecord record = new ExceptionRecord();
        record.setExceptionNo(NoGen.next("EX"));
        record.setBatchId(batch.getId());
        record.setBatchNo(batch.getBatchNo());
        record.setBagId(bag != null ? bag.getId() : null);
        record.setBagNo(bag != null ? bag.getBagNo() : null);
        record.setType(type);
        record.setDescription(description);
        exceptionRecordRepository.save(record);
    }

    @Transactional(readOnly = true)
    public RecycleBatch get(String batchNo) {
        return batchRepository.findByBatchNo(batchNo)
                .orElseThrow(() -> BizException.notFound("批次不存在: " + batchNo));
    }

    @Transactional(readOnly = true)
    public List<RecycleBatch> listByResident(Long residentId) {
        return batchRepository.findByResidentIdOrderByCreatedAtDesc(residentId);
    }

    /** 批次全链路追溯：预约 -> 称重 -> 封袋 -> 复核 -> 暂存 -> 交接 -> 积分 -> 异常 */
    @Transactional(readOnly = true)
    public Map<String, Object> trace(String batchNo) {
        RecycleBatch batch = get(batchNo);
        Appointment appointment = appointmentRepository.findById(batch.getAppointmentId()).orElseThrow();
        List<DrugBag> bags = bagRepository.findByBatchId(batch.getId());

        List<Map<String, Object>> bagViews = new ArrayList<>();
        for (DrugBag bag : bags) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("bag", bag);
            view.put("reviewTasks", reviewTaskRepository.findByBagId(bag.getId()));
            view.put("storageEntry", storageEntryRepository.findByBagIdAndOutAtIsNull(bag.getId()).orElse(null));
            List<HandoverItem> items = handoverItemRepository.findByBagId(bag.getId());
            List<Map<String, Object>> handovers = new ArrayList<>();
            for (HandoverItem item : items) {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("item", item);
                handoverOrderRepository.findById(item.getOrderId()).ifPresent(o -> h.put("order", o));
                handovers.add(h);
            }
            view.put("handovers", handovers);
            view.put("pointsTransactions", pointsTransactionRepository.findByRefTypeAndRefId("BAG", bag.getBagNo()));
            sharpDepositRepository.findByBagId(bag.getId()).ifPresent(deposit -> {
                view.put("sharpDeposit", deposit);
                sharpContainerRepository.findById(deposit.getContainerId())
                        .ifPresent(c -> view.put("sharpContainer", c));
            });
            bagViews.add(view);
        }

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("appointment", appointment);
        trace.put("batch", batch);
        trace.put("weighRecords", weighRecordRepository.findByBatchIdOrderByCreatedAtAsc(batch.getId()));
        trace.put("bags", bagViews);
        trace.put("exceptions", exceptionRecordRepository.findByBatchId(batch.getId()));
        List<SharpDeposit> sharpDeposits = sharpDepositRepository.findByBatchId(batch.getId());
        trace.put("sharpDeposits", sharpDeposits);
        trace.put("sharpContainers", sharpDeposits.stream()
                .map(d -> sharpContainerRepository.findById(d.getContainerId()).orElse(null))
                .filter(Objects::nonNull).distinct().toList());
        return trace;
    }
}
