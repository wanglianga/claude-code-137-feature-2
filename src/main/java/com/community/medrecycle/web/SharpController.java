package com.community.medrecycle.web;

import com.community.medrecycle.config.AuthContext;
import com.community.medrecycle.domain.Account;
import com.community.medrecycle.domain.SharpContainer;
import com.community.medrecycle.domain.SharpNotice;
import com.community.medrecycle.domain.enums.Role;
import com.community.medrecycle.domain.enums.SharpNoticeLevel;
import com.community.medrecycle.domain.enums.SharpNoticeTarget;
import com.community.medrecycle.service.SharpService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 针剂安全封装复核：锐器盒登记/列表、针剂转入（防刺容器+混入药袋+数量+重量+照片确认）、
 * 封箱生成特殊交接标签、容量预警/上限通知。
 */
@RestController
@RequestMapping("/api/sharps")
@RequiredArgsConstructor
public class SharpController {

    private final SharpService sharpService;

    public record RegisterContainerRequest(@NotNull Long siteId, String name,
                                           @Positive int capacityItems, @Positive double capacityWeightKg) {
    }

    /** 登记锐器盒（管理员） */
    @PostMapping("/containers")
    public SharpContainer register(@Validated @RequestBody RegisterContainerRequest request) {
        AuthContext.requireRole(Role.ADMIN);
        return sharpService.registerContainer(request.siteId(), request.name(),
                request.capacityItems(), request.capacityWeightKg());
    }

    /** 站点锐器盒列表（含使用率、状态、特殊交接标签） */
    @GetMapping("/containers")
    public List<Map<String, Object>> containers(@RequestParam Long siteId) {
        AuthContext.requireRole(Role.RECYCLER, Role.ADMIN, Role.REVIEWER);
        return sharpService.containers(siteId);
    }

    /** 锐器盒详情：投放记录与容量通知 */
    @GetMapping("/containers/{containerNo}")
    public Map<String, Object> detail(@PathVariable String containerNo) {
        AuthContext.requireRole(Role.RECYCLER, Role.ADMIN, Role.REVIEWER);
        return sharpService.detail(containerNo);
    }

    public record DepositRequest(@NotBlank String containerNo, @Positive int itemCount,
                                 boolean punctureProofContainer, boolean mixedWithRegularBag,
                                 @NotBlank String photoUrls) {
    }

    /** 针剂转入锐器盒：回收员现场安全封装复核（未封装针剂不得进入普通暂存柜） */
    @PostMapping("/bags/{bagNo}/deposit")
    public Map<String, Object> deposit(@PathVariable String bagNo,
                                       @Validated @RequestBody DepositRequest request) {
        Account recycler = AuthContext.requireRole(Role.RECYCLER, Role.ADMIN);
        return sharpService.deposit(recycler.getId(), bagNo,
                new SharpService.DepositRequest(request.containerNo(), request.itemCount(),
                        request.punctureProofContainer(), request.mixedWithRegularBag(), request.photoUrls()));
    }

    public record SealRequest(@NotBlank String labelPhotoUrls) {
    }

    /** 封箱：生成特殊交接标签（SHL），封箱后整盒随交接单移交 */
    @PostMapping("/containers/{containerNo}/seal")
    public SharpContainer seal(@PathVariable String containerNo, @Validated @RequestBody SealRequest request) {
        Account recycler = AuthContext.requireRole(Role.RECYCLER, Role.ADMIN);
        return sharpService.seal(recycler.getId(), containerNo, request.labelPhotoUrls());
    }

    public record NotifyRequest(@NotNull SharpNoticeLevel level, @NotNull SharpNoticeTarget target,
                                String targetName) {
    }

    /** 手动补发容量通知（预警/上限 -> 环保公司或上级回收点） */
    @PostMapping("/containers/{containerNo}/notify")
    public SharpNotice notify(@PathVariable String containerNo, @Validated @RequestBody NotifyRequest request) {
        Account operator = AuthContext.requireRole(Role.RECYCLER, Role.ADMIN);
        return sharpService.notifyManually(operator.getId(), containerNo,
                request.level(), request.target(), request.targetName());
    }

    /** 容量通知记录 */
    @GetMapping("/notices")
    public List<SharpNotice> notices(@RequestParam(required = false) Long siteId) {
        AuthContext.requireRole(Role.RECYCLER, Role.ADMIN);
        return sharpService.notices(siteId);
    }
}
