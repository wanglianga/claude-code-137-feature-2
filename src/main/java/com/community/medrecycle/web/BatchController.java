package com.community.medrecycle.web;

import com.community.medrecycle.config.AuthContext;
import com.community.medrecycle.domain.Account;
import com.community.medrecycle.domain.RecycleBatch;
import com.community.medrecycle.domain.enums.DrugCategory;
import com.community.medrecycle.domain.enums.Role;
import com.community.medrecycle.service.BatchService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/batches")
@RequiredArgsConstructor
public class BatchController {

    private final BatchService batchService;

    public record CheckInRequest(@NotBlank String appointmentNo) {
    }

    /** 回收员扫码登记：预约 -> 批次 */
    @PostMapping("/check-in")
    public RecycleBatch checkIn(@Validated @RequestBody CheckInRequest request) {
        Account recycler = AuthContext.requireRole(Role.RECYCLER, Role.ADMIN);
        return batchService.checkIn(recycler.getId(), request.appointmentNo());
    }

    public record WeighItemRequest(@NotNull DrugCategory category, @Positive double weightKg,
                                   String sealNo, String photoUrls, boolean dispute,
                                   boolean punctureProofContainer, boolean mixedWithRegularBag,
                                   int sharpItemCount) {
    }

    public record WeighRequest(@NotEmpty @Valid List<WeighItemRequest> items) {
    }

    /** 现场分类称重封袋；注射器/针剂必须登记锐器数量、照片及防刺容器/混入普通药袋确认 */
    @PostMapping("/{batchNo}/weigh")
    public RecycleBatch weigh(@PathVariable String batchNo, @Validated @RequestBody WeighRequest request) {
        Account recycler = AuthContext.requireRole(Role.RECYCLER, Role.ADMIN);
        List<BatchService.WeighItem> items = request.items().stream()
                .map(i -> new BatchService.WeighItem(i.category(), i.weightKg(), i.sealNo(), i.photoUrls(),
                        i.dispute(), i.punctureProofContainer(), i.mixedWithRegularBag(), i.sharpItemCount()))
                .toList();
        return batchService.weigh(recycler.getId(), batchNo, items);
    }

    /** 称重完成 */
    @PostMapping("/{batchNo}/finish-weighing")
    public RecycleBatch finishWeighing(@PathVariable String batchNo) {
        AuthContext.requireRole(Role.RECYCLER, Role.ADMIN);
        return batchService.finishWeighing(batchNo);
    }

    @GetMapping("/{batchNo}")
    public RecycleBatch get(@PathVariable String batchNo) {
        AuthContext.require();
        return batchService.get(batchNo);
    }

    /** 批次全链路追溯（居民与工作人员均可查） */
    @GetMapping("/{batchNo}/trace")
    public Map<String, Object> trace(@PathVariable String batchNo) {
        AuthContext.require();
        return batchService.trace(batchNo);
    }

    @GetMapping("/mine")
    public List<RecycleBatch> mine() {
        Account resident = AuthContext.requireRole(Role.RESIDENT);
        return batchService.listByResident(resident.getId());
    }
}
