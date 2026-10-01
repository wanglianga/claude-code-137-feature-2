package com.community.medrecycle.web;

import com.community.medrecycle.config.AuthContext;
import com.community.medrecycle.domain.Account;
import com.community.medrecycle.domain.SharpsContainer;
import com.community.medrecycle.domain.SharpsNotification;
import com.community.medrecycle.domain.enums.Role;
import com.community.medrecycle.domain.enums.SharpsStatus;
import com.community.medrecycle.service.SharpsService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 针剂安全封装复核：锐器盒登记/转入/封盒/重新封装、容量预警通知 */
@RestController
@RequestMapping("/api/sharps")
@RequiredArgsConstructor
public class SharpsController {

    private final SharpsService sharpsService;

    public record RegisterRequest(@NotNull Long siteId, @NotBlank String name,
                                  @Positive int capacitySharps, @Positive double capacityWeightKg,
                                  Double warnRatio) {
    }

    /** 社区管理员登记锐器盒（防刺安全容器） */
    @PostMapping("/containers")
    public SharpsContainer register(@Validated @RequestBody RegisterRequest request) {
        AuthContext.requireRole(Role.ADMIN);
        return sharpsService.registerContainer(request.siteId(), request.name(),
                request.capacitySharps(), request.capacityWeightKg(), request.warnRatio());
    }

    /** 锐器盒列表（可按状态过滤：IN_USE/SEALED/HANDED_OVER/DISPOSED） */
    @GetMapping("/containers")
    public List<SharpsContainer> list(@RequestParam Long siteId,
                                      @RequestParam(required = false) SharpsStatus status) {
        AuthContext.requireRole(Role.RECYCLER, Role.ADMIN, Role.REVIEWER);
        return sharpsService.list(siteId, status);
    }

    /** 锐器盒详情：转入记录、特殊标签、容量与通知 */
    @GetMapping("/containers/{containerNo}")
    public Map<String, Object> detail(@PathVariable String containerNo) {
        AuthContext.requireRole(Role.RECYCLER, Role.ADMIN, Role.REVIEWER);
        return sharpsService.detail(containerNo);
    }

    public record DepositRequest(@NotBlank String containerNo, @NotBlank String bagNo,
                                 @NotBlank String photoUrls, String note) {
    }

    /** 针剂复核通过后转入锐器盒（普通暂存柜拒绝接收针剂） */
    @PostMapping("/deposit")
    public SharpsService.DepositResult deposit(@Validated @RequestBody DepositRequest request) {
        Account operator = AuthContext.requireRole(Role.RECYCLER, Role.ADMIN);
        return sharpsService.deposit(operator.getId(), request.containerNo(),
                request.bagNo(), request.photoUrls(), request.note());
    }

    /** 封盒：生成/确认特殊交接标签，封盒后可随交接单整体清运 */
    @PostMapping("/containers/{containerNo}/seal")
    public SharpsContainer seal(@PathVariable String containerNo) {
        AuthContext.requireRole(Role.RECYCLER, Role.ADMIN);
        return sharpsService.seal(containerNo);
    }

    public record RepackageRequest(@NotBlank String damagedContainerNo, @NotBlank String newContainerNo,
                                   @NotBlank String photoUrls, String note) {
    }

    /** 签收发现包装破损：补充事故记录并将针剂重新封装到全新空锐器盒（生成新标签） */
    @PostMapping("/repackage")
    public SharpsService.RepackResult repackage(@Validated @RequestBody RepackageRequest request) {
        Account operator = AuthContext.requireRole(Role.RECYCLER, Role.ADMIN);
        return sharpsService.repackage(operator.getId(), request.damagedContainerNo(),
                request.newContainerNo(), request.photoUrls(), request.note());
    }

    /** 容量预警通知记录（NEAR_FULL 提前通知 / FULL 停收通知） */
    @GetMapping("/notifications")
    public List<SharpsNotification> notifications(@RequestParam Long siteId) {
        AuthContext.requireRole(Role.RECYCLER, Role.ADMIN, Role.REVIEWER);
        return sharpsService.notifications(siteId);
    }
}
