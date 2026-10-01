package com.community.medrecycle.service;

import com.community.medrecycle.domain.Appointment;
import com.community.medrecycle.domain.DrugBag;
import com.community.medrecycle.domain.RecycleBatch;
import com.community.medrecycle.domain.enums.AppointmentStatus;
import com.community.medrecycle.domain.enums.BagStatus;
import com.community.medrecycle.domain.enums.BatchStatus;
import com.community.medrecycle.repository.AppointmentRepository;
import com.community.medrecycle.repository.DrugBagRepository;
import com.community.medrecycle.repository.RecycleBatchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** 根据袋状态推导批次状态，并同步批次汇总（总重/总积分）与预约状态 */
@Component
@RequiredArgsConstructor
public class BatchRefresher {

    private static final Set<BagStatus> ACCEPTED_FLOW = EnumSet.of(
            BagStatus.ACCEPTED, BagStatus.IN_SHARPS_BOX, BagStatus.STORED,
            BagStatus.HANDOVER_ASSIGNED, BagStatus.HANDED_OVER);

    private final RecycleBatchRepository batchRepository;
    private final DrugBagRepository bagRepository;
    private final AppointmentRepository appointmentRepository;

    @Transactional(propagation = Propagation.REQUIRED)
    public RecycleBatch refresh(Long batchId) {
        RecycleBatch batch = batchRepository.findById(batchId).orElseThrow();
        List<DrugBag> bags = bagRepository.findByBatchId(batchId);

        double totalWeight = bags.stream()
                .filter(b -> b.getStatus() != BagStatus.REVIEW_REJECTED)
                .mapToDouble(DrugBag::getWeightKg).sum();
        int totalPoints = bags.stream()
                .filter(DrugBag::isPointsGranted)
                .mapToInt(DrugBag::getPoints).sum();
        batch.setTotalWeightKg(totalWeight);
        batch.setTotalPoints(totalPoints);

        if (!bags.isEmpty()) {
            boolean anyPendingReview = bags.stream().anyMatch(b -> b.getStatus() == BagStatus.PENDING_REVIEW);
            List<DrugBag> accepted = bags.stream().filter(b -> ACCEPTED_FLOW.contains(b.getStatus())).toList();
            boolean allRejected = accepted.isEmpty()
                    && bags.stream().allMatch(b -> b.getStatus() == BagStatus.REVIEW_REJECTED);

            if (anyPendingReview) {
                batch.setStatus(BatchStatus.IN_REVIEW);
            } else if (allRejected) {
                batch.setStatus(BatchStatus.CLOSED);
            } else if (!accepted.isEmpty()
                    && accepted.stream().allMatch(b -> b.getStatus() == BagStatus.HANDED_OVER)) {
                batch.setStatus(BatchStatus.HANDED_OVER);
            } else if (accepted.stream().anyMatch(b -> b.getStatus() == BagStatus.HANDED_OVER
                    || b.getStatus() == BagStatus.HANDOVER_ASSIGNED)) {
                batch.setStatus(BatchStatus.PARTIALLY_HANDED_OVER);
            } else if (accepted.stream().allMatch(b -> b.getStatus() == BagStatus.STORED
                    || b.getStatus() == BagStatus.IN_SHARPS_BOX)) {
                // 普通袋入暂存柜、针剂入锐器盒，均已安全暂存
                batch.setStatus(BatchStatus.STORED);
            } else {
                batch.setStatus(BatchStatus.POINTS_GRANTED);
            }
        }

        batch.setUpdatedAt(LocalDateTime.now());
        RecycleBatch saved = batchRepository.save(batch);

        // 批次终态时同步预约状态
        if (saved.getStatus() == BatchStatus.HANDED_OVER || saved.getStatus() == BatchStatus.CLOSED) {
            appointmentRepository.findById(saved.getAppointmentId()).ifPresent(appt -> {
                if (appt.getStatus() == AppointmentStatus.CHECKED_IN) {
                    appt.setStatus(AppointmentStatus.COMPLETED);
                    appointmentRepository.save(appt);
                }
            });
        }
        return saved;
    }
}
