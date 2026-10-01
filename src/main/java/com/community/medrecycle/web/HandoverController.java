package com.community.medrecycle.web;

import com.community.medrecycle.config.AuthContext;
import com.community.medrecycle.domain.Account;
import com.community.medrecycle.domain.HandoverOrder;
import com.community.medrecycle.domain.enums.Role;
import com.community.medrecycle.service.HandoverService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/handovers")
@RequiredArgsConstructor
public class HandoverController {

    private final HandoverService handoverService;

    public record CreateRequest(@NotNull Long siteId, @NotBlank String companyName,
                                @NotBlank String destination, List<String> bagNos,
                                List<String> sharpsContainerNos, boolean force) {
    }

    /** 管理员发起环保交接（默认取站点全部暂存袋+已封盒锐器盒，校验上门量） */
    @PostMapping
    public HandoverOrder create(@Validated @RequestBody CreateRequest request) {
        AuthContext.requireRole(Role.ADMIN);
        return handoverService.create(request.siteId(), request.companyName(),
                request.destination(), request.bagNos(), request.sharpsContainerNos(), request.force());
    }

    /** 交出方签收（双人交接时需两人各签一次） */
    @PostMapping("/{orderNo}/sign")
    public HandoverOrder sign(@PathVariable String orderNo) {
        Account operator = AuthContext.requireRole(Role.RECYCLER, Role.ADMIN);
        return handoverService.sign(operator.getId(), orderNo);
    }

    public record ItemResultRequest(@NotBlank String bagNo, boolean accepted, String rejectReason) {
    }

    public record ContainerResultRequest(@NotBlank String containerNo, boolean accepted,
                                         Integer receivedSharpsCount, Double receivedWeightKg,
                                         boolean packagingDamaged, String rejectReason) {
    }

    public record CompleteRequest(@NotBlank String receiverName, String photoUrls,
                                  @Valid List<ItemResultRequest> items,
                                  @Valid List<ContainerResultRequest> sharpsContainers) {
    }

    /** 环保公司签收完成：逐袋确认、锐器盒按数量/重量逐盒确认，拒收/破损自动生成异常 */
    @PostMapping("/{orderNo}/complete")
    public HandoverOrder complete(@PathVariable String orderNo,
                                  @Validated @RequestBody CompleteRequest request) {
        AuthContext.requireRole(Role.RECYCLER, Role.ADMIN);
        List<HandoverService.ItemResult> items = request.items() == null ? List.of()
                : request.items().stream()
                .map(i -> new HandoverService.ItemResult(i.bagNo(), i.accepted(), i.rejectReason()))
                .toList();
        List<HandoverService.ContainerResult> containers = request.sharpsContainers() == null ? List.of()
                : request.sharpsContainers().stream()
                .map(c -> new HandoverService.ContainerResult(c.containerNo(), c.accepted(),
                        c.receivedSharpsCount(), c.receivedWeightKg(), c.packagingDamaged(), c.rejectReason()))
                .toList();
        return handoverService.complete(orderNo, request.receiverName(), request.photoUrls(), items, containers);
    }

    /** 交接单详情：去向、重量、照片、签收人、积分发放结果 */
    @GetMapping("/{orderNo}")
    public Map<String, Object> detail(@PathVariable String orderNo) {
        AuthContext.require();
        return handoverService.detail(orderNo);
    }

    @GetMapping
    public List<HandoverOrder> list(@RequestParam Long siteId) {
        AuthContext.requireRole(Role.RECYCLER, Role.ADMIN);
        return handoverService.listBySite(siteId);
    }
}
