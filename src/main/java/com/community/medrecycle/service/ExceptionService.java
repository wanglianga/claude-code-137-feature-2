package com.community.medrecycle.service;

import com.community.medrecycle.config.BizException;
import com.community.medrecycle.config.NoGen;
import com.community.medrecycle.domain.*;
import com.community.medrecycle.domain.enums.BagStatus;
import com.community.medrecycle.domain.enums.ExceptionStatus;
import com.community.medrecycle.domain.enums.ExceptionType;
import com.community.medrecycle.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 异常处理：夹带未过期药/处方信息泄露/针剂未封装/液体泄漏/称重争议/兑换失败/公司拒收 */
@Service
@RequiredArgsConstructor
public class ExceptionService {

    private final ExceptionRecordRepository exceptionRepository;
    private final RecycleBatchRepository batchRepository;
    private final DrugBagRepository bagRepository;
    private final AppointmentRepository appointmentRepository;
    private final ReviewTaskRepository reviewTaskRepository;
    private final PointsService pointsService;
    private final PointsRuleService pointsRuleService;
    private final BatchRefresher batchRefresher;

    /** 现场/居民上报异常，按类型联动袋状态与积分 */
    @Transactional
    public ExceptionRecord raise(String batchNo, String bagNo, ExceptionType type, String description) {
        RecycleBatch batch = null;
        DrugBag bag = null;
        if (bagNo != null && !bagNo.isBlank()) {
            bag = bagRepository.findByBagNo(bagNo)
                    .orElseThrow(() -> BizException.notFound("封袋不存在: " + bagNo));
            batch = batchRepository.findById(bag.getBatchId()).orElseThrow();
        } else if (batchNo != null && !batchNo.isBlank()) {
            batch = batchRepository.findByBatchNo(batchNo)
                    .orElseThrow(() -> BizException.notFound("批次不存在: " + batchNo));
        } else if (type != ExceptionType.EXCHANGE_FAILED) {
            throw BizException.badRequest("除积分兑换失败外，异常必须关联批次或封袋");
        }

        ExceptionRecord record = new ExceptionRecord();
        record.setExceptionNo(NoGen.next("EX"));
        record.setType(type);
        record.setDescription(description);
        if (batch != null) {
            record.setBatchId(batch.getId());
            record.setBatchNo(batch.getBatchNo());
        }
        if (bag != null) {
            record.setBagId(bag.getId());
            record.setBagNo(bag.getBagNo());
        }
        ExceptionRecord saved = exceptionRepository.save(record);

        if (bag != null && batch != null) {
            applySideEffect(saved, batch, bag);
            batchRefresher.refresh(batch.getId());
        }
        return saved;
    }

    /** 异常副作用：让批次、袋、复核、积分联动起来 */
    private void applySideEffect(ExceptionRecord record, RecycleBatch batch, DrugBag bag) {
        switch (record.getType()) {
            case NON_EXPIRED_DRUG -> {
                // 夹带未过期药：扣回已发积分并退回人工复核
                if (bag.isPointsGranted()) {
                    pointsService.revoke(batch.getResidentId(), bag.getPoints(), "BAG", bag.getBagNo(),
                            "夹带未过期药扣回，异常单 " + record.getExceptionNo());
                    bag.setPointsGranted(false);
                }
                sendBackToReview(batch, bag, "NON_EXPIRED_DRUG");
            }
            case PRESCRIPTION_INFO_EXPOSED, UNSAFE_INJECTION, LIQUID_LEAK -> {
                // 隐私泄露/针剂未安全封装/液体泄漏：须人工复核处置后才能继续流转
                if (bag.getStatus() != BagStatus.HANDED_OVER) {
                    sendBackToReview(batch, bag, record.getType().name());
                }
            }
            case WEIGHT_DISPUTE, EXCHANGE_FAILED, COMPANY_REJECT -> {
                // 称重争议走 reweigh 解决；兑换失败/公司拒收仅记录跟踪
            }
        }
    }

    private void sendBackToReview(RecycleBatch batch, DrugBag bag, String reason) {
        bag.setStatus(BagStatus.PENDING_REVIEW);
        bagRepository.save(bag);
        ReviewTask task = new ReviewTask();
        task.setTaskNo(NoGen.next("RT"));
        task.setBatchId(batch.getId());
        task.setBagId(bag.getId());
        task.setBagNo(bag.getBagNo());
        task.setReason(reason);
        reviewTaskRepository.save(task);
    }

    /** 交接完成时自动记录环保公司拒收异常 */
    @Transactional
    public ExceptionRecord recordAuto(ExceptionType type, DrugBag bag, String description) {
        RecycleBatch batch = batchRepository.findById(bag.getBatchId()).orElseThrow();
        ExceptionRecord record = new ExceptionRecord();
        record.setExceptionNo(NoGen.next("EX"));
        record.setType(type);
        record.setDescription(description);
        record.setBatchId(batch.getId());
        record.setBatchNo(batch.getBatchNo());
        record.setBagId(bag.getId());
        record.setBagNo(bag.getBagNo());
        return exceptionRepository.save(record);
    }

    /** 环保公司整盒拒收锐器盒（非包装破损） */
    @Transactional
    public ExceptionRecord recordContainerReject(com.community.medrecycle.domain.SharpContainer container,
                                                 String reason) {
        ExceptionRecord record = new ExceptionRecord();
        record.setExceptionNo(NoGen.next("EX"));
        record.setType(ExceptionType.COMPANY_REJECT);
        record.setSharpContainerId(container.getId());
        record.setSharpContainerNo(container.getContainerNo());
        record.setDescription("环保公司整盒拒收锐器盒 " + container.getContainerNo()
                + "（特殊交接标签 " + container.getHandoffLabelNo() + "），原因: "
                + (reason == null || reason.isBlank() ? "未说明" : reason) + "，锐器盒退回回收点待重新交接");
        return exceptionRepository.save(record);
    }

    /** 锐器盒包装破损事故（随交接事务提交）：回收点补充事故记录，随后停用锐器盒并重新封装 */
    @Transactional
    public ExceptionRecord recordPackageDamage(com.community.medrecycle.domain.SharpContainer container,
                                               Long orderId, String note) {
        ExceptionRecord record = new ExceptionRecord();
        record.setExceptionNo(NoGen.next("EX"));
        record.setType(ExceptionType.PACKAGE_DAMAGED);
        record.setSharpContainerId(container.getId());
        record.setSharpContainerNo(container.getContainerNo());
        record.setDescription("环保公司签收时发现锐器盒包装破损（盒号 " + container.getContainerNo()
                + "，特殊交接标签 " + container.getHandoffLabelNo() + "）："
                + (note == null || note.isBlank() ? "未说明" : note)
                + "。回收点已补充事故记录并重新封装，该盒停用，针剂退回重新转入新锐器盒。"
                + (orderId != null ? " 关联交接单ID " + orderId + "。" : ""));
        return exceptionRepository.save(record);
    }

    /**
     * 针剂安全封装复核未通过（独立事务）：
     * 留存 UNSAFE_INJECTION 异常并把针剂袋退回人工复核，即使外层投放事务回滚，事故记录也保留。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ExceptionRecord handleUnsafeInjection(Long bagId, int itemCount, double weightKg,
                                                 boolean punctureProof, boolean mixedBag) {
        DrugBag bag = bagRepository.findById(bagId).orElseThrow();
        RecycleBatch batch = batchRepository.findById(bag.getBatchId()).orElseThrow();

        ExceptionRecord record = new ExceptionRecord();
        record.setExceptionNo(NoGen.next("EX"));
        record.setBatchId(batch.getId());
        record.setBatchNo(batch.getBatchNo());
        record.setBagId(bag.getId());
        record.setBagNo(bag.getBagNo());
        record.setType(ExceptionType.UNSAFE_INJECTION);
        record.setDescription("针剂安全封装复核未通过："
                + (!punctureProof ? "回收员确认居民未使用防刺容器；" : "")
                + (mixedBag ? "针剂混入普通药袋；" : "")
                + "数量 " + itemCount + " 支，重量 " + weightKg
                + "kg。未封装针剂禁止进入普通暂存柜，须重新规范封装并复核后转入锐器盒");
        exceptionRepository.save(record);

        if (bag.getStatus() != BagStatus.REVIEW_REJECTED) {
            bag.setStatus(BagStatus.PENDING_REVIEW);
            bag.setSharpContainerId(null);
            bagRepository.save(bag);
            boolean exists = reviewTaskRepository.findByBagIdAndStatus(bag.getId(),
                    com.community.medrecycle.domain.enums.ReviewStatus.PENDING).stream().findAny().isPresent();
            if (!exists) {
                ReviewTask task = new ReviewTask();
                task.setTaskNo(NoGen.next("RT"));
                task.setBatchId(batch.getId());
                task.setBagId(bag.getId());
                task.setBagNo(bag.getBagNo());
                task.setReason(ExceptionType.UNSAFE_INJECTION.name());
                reviewTaskRepository.save(task);
            }
        }
        return record;
    }

    @Transactional
    public ExceptionRecord process(Long handlerId, String exceptionNo) {
        ExceptionRecord record = get(exceptionNo);
        if (record.getStatus() != ExceptionStatus.OPEN) {
            throw BizException.conflict("异常当前状态不可受理: " + record.getStatus());
        }
        record.setStatus(ExceptionStatus.PROCESSING);
        record.setHandlerId(handlerId);
        return exceptionRepository.save(record);
    }

    @Transactional
    public ExceptionRecord resolve(Long handlerId, String exceptionNo, String resolution) {
        ExceptionRecord record = get(exceptionNo);
        if (record.getStatus() == ExceptionStatus.RESOLVED) {
            throw BizException.conflict("异常已办结");
        }
        record.setStatus(ExceptionStatus.RESOLVED);
        record.setHandlerId(handlerId);
        record.setResolution(resolution);
        record.setResolvedAt(LocalDateTime.now());
        return exceptionRepository.save(record);
    }

    /** 称重争议复秤：调整袋重并按预约快照重算积分（多退少补），随后办结异常 */
    @Transactional
    public ExceptionRecord reweigh(Long handlerId, String exceptionNo, double newWeightKg) {
        if (newWeightKg <= 0) {
            throw BizException.badRequest("复秤重量必须大于 0");
        }
        ExceptionRecord record = get(exceptionNo);
        if (record.getType() != ExceptionType.WEIGHT_DISPUTE) {
            throw BizException.badRequest("仅称重争议异常支持复秤");
        }
        if (record.getStatus() == ExceptionStatus.RESOLVED) {
            throw BizException.conflict("异常已办结");
        }
        DrugBag bag = bagRepository.findById(record.getBagId())
                .orElseThrow(() -> BizException.notFound("关联封袋不存在"));
        if (bag.getStatus() == BagStatus.HANDED_OVER) {
            throw BizException.conflict("封袋已交接，不可复秤");
        }
        RecycleBatch batch = batchRepository.findById(bag.getBatchId()).orElseThrow();
        Appointment appointment = appointmentRepository.findById(batch.getAppointmentId()).orElseThrow();

        double oldWeight = bag.getWeightKg();
        int oldPoints = bag.getPoints();
        int newPoints = pointsRuleService.pointsFor(
                appointment.getPointsRuleSnapshot(), bag.getCategory(), newWeightKg, batch.getPointsMultiplier());

        bag.setWeightKg(newWeightKg);
        bag.setPoints(newPoints);
        bagRepository.save(bag);

        if (bag.isPointsGranted() && newPoints != oldPoints) {
            pointsService.adjust(batch.getResidentId(), newPoints - oldPoints, "BAG", bag.getBagNo(),
                    String.format("称重争议复秤 %.2fkg -> %.2fkg，积分 %d -> %d",
                            oldWeight, newWeightKg, oldPoints, newPoints));
        }

        batchRefresher.refresh(batch.getId());
        return resolve(handlerId, exceptionNo,
                String.format("复秤 %.2fkg -> %.2fkg，积分 %d -> %d", oldWeight, newWeightKg, oldPoints, newPoints));
    }

    @Transactional(readOnly = true)
    public ExceptionRecord get(String exceptionNo) {
        return exceptionRepository.findByExceptionNo(exceptionNo)
                .orElseThrow(() -> BizException.notFound("异常不存在: " + exceptionNo));
    }

    @Transactional(readOnly = true)
    public List<ExceptionRecord> list(ExceptionStatus status) {
        return status == null
                ? exceptionRepository.findAll()
                : exceptionRepository.findByStatusOrderByCreatedAtDesc(status);
    }
}
