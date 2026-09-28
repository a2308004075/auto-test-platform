/**
 * @author HXN
 * @date 2026-08-20 15:34
 * @description 执行管理控制器
 */
package com.platform.execution.controller;

import com.platform.common.response.ApiResponse;
import com.platform.common.response.PageResponse;
import com.platform.execution.dto.ExecutionResponse;
import com.platform.execution.dto.ExecutionStartRequest;
import com.platform.execution.dto.ManualCaseResultUpdateRequest;
import com.platform.execution.dto.ManualExecutionResponse;
import com.platform.execution.dto.RoundResultUpdateRequest;
import com.platform.execution.dto.TestResultResponse;
import com.platform.execution.service.ExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

/**
 * 测试执行管理接口
 */
@RestController
@RequiredArgsConstructor
public class ExecutionController {

    private final ExecutionService executionService;

    /**
     * 触发执行
     */
    @PostMapping("/api/v1/plans/{planId}/executions")
    public ApiResponse<ExecutionResponse> start(@PathVariable Long planId,
                                                @Valid @RequestBody(required = false) ExecutionStartRequest request) {
        if (request == null) {
            request = new ExecutionStartRequest();
        }
        return ApiResponse.ok(executionService.startExecution(planId, request));
    }

    /**
     * 分页查询执行记录
     */
    @GetMapping("/api/v1/projects/{projectId}/executions")
    public ApiResponse<PageResponse<ExecutionResponse>> list(@PathVariable Long projectId,
                                                             @RequestParam(required = false) String planName,
                                                             @RequestParam(required = false) Long environmentId,
                                                             @RequestParam(required = false) String status,
                                                             @RequestParam(required = false) String triggerType,
                                                             @RequestParam(required = false) String startedAtFrom,
                                                             @RequestParam(required = false) String startedAtTo,
                                                             @RequestParam(required = false) String finishedAtFrom,
                                                             @RequestParam(required = false) String finishedAtTo,
                                                             @RequestParam(defaultValue = "1") int page,
                                                             @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(executionService.listExecutions(projectId, planName, environmentId,
                status, triggerType, startedAtFrom, startedAtTo, finishedAtFrom, finishedAtTo, page, pageSize));
    }

    /**
     * 获取执行详情
     */
    @GetMapping("/api/v1/executions/{executionId}")
    public ApiResponse<ExecutionResponse> get(@PathVariable Long executionId) {
        return ApiResponse.ok(executionService.getExecution(executionId));
    }

    /**
     * 获取执行结果明细
     */
    @GetMapping("/api/v1/executions/{executionId}/results")
    public ApiResponse<List<TestResultResponse>> getResults(@PathVariable Long executionId) {
        return ApiResponse.ok(executionService.getResults(executionId));
    }

    /**
     * 取消执行
     */
    @PostMapping("/api/v1/executions/{executionId}/cancel")
    public ApiResponse<ExecutionResponse> cancel(@PathVariable Long executionId) {
        return ApiResponse.ok(executionService.cancelExecution(executionId));
    }

    /**
     * 更新手动化用例执行结果
     */
    @PostMapping("/api/v1/executions/{executionId}/manual-results")
    public ApiResponse<TestResultResponse> updateManualCaseResult(@PathVariable Long executionId,
                                                                   @Valid @RequestBody ManualCaseResultUpdateRequest request) {
        return ApiResponse.ok(executionService.updateManualCaseResult(executionId, request));
    }

    /**
     * 获取或创建计划当前进行中的手动执行单（执行页加载）
     */
    @GetMapping("/api/v1/plans/{planId}/current-execution")
    public ApiResponse<ManualExecutionResponse> getCurrentExecution(@PathVariable Long planId) {
        return ApiResponse.ok(executionService.getCurrentManualExecution(planId));
    }

    /**
     * 更新多轮结果单元格（执行页即时保存）
     */
    @PostMapping("/api/v1/executions/{executionId}/round-results")
    public ApiResponse<TestResultResponse> updateRoundResult(@PathVariable Long executionId,
                                                             @Valid @RequestBody RoundResultUpdateRequest request) {
        return ApiResponse.ok(executionService.updateRoundResult(executionId, request));
    }

    /**
     * 手动执行完成（快照列定义，形成测试记录）
     */
    @PostMapping("/api/v1/executions/{executionId}/complete")
    public ApiResponse<ExecutionResponse> complete(@PathVariable Long executionId) {
        return ApiResponse.ok(executionService.completeManualExecution(executionId));
    }
}
