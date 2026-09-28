/**
 * @author HXN
 * @date 2026-08-20 15:34
 * @description 测试计划管理控制器
 */
package com.platform.execution.controller;

import com.platform.common.response.ApiResponse;
import com.platform.common.response.PageResponse;
import com.platform.execution.dto.PlanCopyRequest;
import com.platform.execution.dto.PlanCreateRequest;
import com.platform.execution.dto.PlanResponse;
import com.platform.execution.dto.PlanUpdateRequest;
import com.platform.execution.dto.ResultColumnCreateRequest;
import com.platform.execution.dto.ResultColumnRenameRequest;
import com.platform.execution.dto.ResultColumnResponse;
import com.platform.execution.service.PlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

/**
 * 测试计划管理接口
 */
@RestController
@RequiredArgsConstructor
public class PlanController {

    private final PlanService planService;

    /**
     * 分页查询测试计划
     *
     * @param groupId      分组 ID（不传=全部，0=未分组，正数=指定分组含子分组）
     * @param suiteKeyword 关联套件名称关键字（按项目下套件名称模糊匹配）
     */
    @GetMapping("/api/v1/projects/{projectId}/plans")
    public ApiResponse<PageResponse<PlanResponse>> list(@PathVariable Long projectId,
                                                         @RequestParam(required = false) String keyword,
                                                         @RequestParam(required = false) Long groupId,
                                                         @RequestParam(required = false) String triggerType,
                                                         @RequestParam(required = false) Long environmentId,
                                                         @RequestParam(required = false) Integer status,
                                                         @RequestParam(required = false) String updateBegin,
                                                         @RequestParam(required = false) String updateEnd,
                                                         @RequestParam(required = false) String suiteKeyword,
                                                         @RequestParam(required = false) String planType,
                                                         @RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(planService.listPlans(projectId, keyword, groupId,
                triggerType, environmentId, status, updateBegin, updateEnd, suiteKeyword, planType, page, pageSize));
    }

    /**
     * 创建测试计划
     */
    @PostMapping("/api/v1/projects/{projectId}/plans")
    public ApiResponse<PlanResponse> create(@PathVariable Long projectId,
                                            @Valid @RequestBody PlanCreateRequest request) {
        request.setProjectId(projectId);
        return ApiResponse.ok(planService.createPlan(request));
    }

    /**
     * 获取计划详情
     */
    @GetMapping("/api/v1/plans/{planId}")
    public ApiResponse<PlanResponse> get(@PathVariable Long planId) {
        return ApiResponse.ok(planService.getPlan(planId));
    }

    /**
     * 更新计划
     */
    @PostMapping("/api/v1/plans/{planId}")
    public ApiResponse<PlanResponse> update(@PathVariable Long planId,
                                            @Valid @RequestBody PlanUpdateRequest request) {
        return ApiResponse.ok(planService.updatePlan(planId, request));
    }

    /**
     * 复制计划（基础信息与关联内容随源计划，名称/分组由请求指定；不复制执行记录与结果列）
     */
    @PostMapping("/api/v1/plans/{planId}/copy")
    public ApiResponse<PlanResponse> copy(@PathVariable Long planId,
                                          @Valid @RequestBody PlanCopyRequest request) {
        return ApiResponse.ok(planService.copyPlan(planId, request));
    }

    /**
     * 删除计划
     */
    @PostMapping("/api/v1/plans/{planId}/delete")
    public ApiResponse<Void> delete(@PathVariable Long planId) {
        planService.deletePlan(planId);
        return ApiResponse.ok();
    }

    // ===== 计划级自定义测试结果列（手动计划执行页“多轮结果列”，历次执行共享） =====

    /**
     * 查询计划的自定义结果列定义
     */
    @GetMapping("/api/v1/plans/{planId}/result-columns")
    public ApiResponse<List<ResultColumnResponse>> listResultColumns(@PathVariable Long planId) {
        return ApiResponse.ok(planService.listResultColumns(planId));
    }

    /**
     * 添加自定义结果列（追加到末尾）
     */
    @PostMapping("/api/v1/plans/{planId}/result-columns")
    public ApiResponse<ResultColumnResponse> createResultColumn(@PathVariable Long planId,
                                                                @Valid @RequestBody ResultColumnCreateRequest request) {
        return ApiResponse.ok(planService.createResultColumn(planId, request));
    }

    /**
     * 重命名自定义结果列
     */
    @PostMapping("/api/v1/result-columns/{columnId}")
    public ApiResponse<ResultColumnResponse> renameResultColumn(@PathVariable Long columnId,
                                                                @Valid @RequestBody ResultColumnRenameRequest request) {
        return ApiResponse.ok(planService.renameResultColumn(columnId, request));
    }

    /**
     * 删除自定义结果列
     */
    @PostMapping("/api/v1/result-columns/{columnId}/delete")
    public ApiResponse<Void> deleteResultColumn(@PathVariable Long columnId) {
        planService.deleteResultColumn(columnId);
        return ApiResponse.ok();
    }
}
