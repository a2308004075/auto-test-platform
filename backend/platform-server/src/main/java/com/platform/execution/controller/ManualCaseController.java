/**
 * @author HXN
 * @date 2026-08-30
 * @description 手动化用例管理控制器
 */
package com.platform.execution.controller;

import com.platform.common.response.ApiResponse;
import com.platform.common.response.PageResponse;
import com.platform.execution.dto.ManualCaseAttachmentResponse;
import com.platform.execution.dto.ManualCaseCreateRequest;
import com.platform.execution.dto.ManualCaseResponse;
import com.platform.execution.dto.ManualCaseUpdateRequest;
import com.platform.execution.service.ManualCaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * 手动化用例管理接口
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/manual-cases")
@RequiredArgsConstructor
public class ManualCaseController {

    private final ManualCaseService manualCaseService;

    /**
     * 分页查询手动化用例
     */
    @GetMapping
    public ApiResponse<PageResponse<ManualCaseResponse>> list(@PathVariable Long projectId,
                                                               @RequestParam(required = false) Long groupId,
                                                               @RequestParam(required = false) String keyword,
                                                               @RequestParam(required = false) String caseStatus,
                                                               @RequestParam(required = false) String customFilters,
                                                               @RequestParam(defaultValue = "1") int page,
                                                               @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(manualCaseService.listCases(projectId, groupId, keyword, caseStatus, customFilters, page, pageSize));
    }

    /**
     * 创建手动化用例
     */
    @PostMapping
    public ApiResponse<ManualCaseResponse> create(@PathVariable Long projectId,
                                                   @Valid @RequestBody ManualCaseCreateRequest request) {
        return ApiResponse.ok(manualCaseService.createCase(projectId, request));
    }

    /**
     * 获取用例详情
     */
    @GetMapping("/{caseId}")
    public ApiResponse<ManualCaseResponse> get(@PathVariable Long projectId,
                                                @PathVariable Long caseId) {
        return ApiResponse.ok(manualCaseService.getCase(caseId));
    }

    /**
     * 更新手动化用例
     */
    @PostMapping("/{caseId}")
    public ApiResponse<ManualCaseResponse> update(@PathVariable Long projectId,
                                                   @PathVariable Long caseId,
                                                   @Valid @RequestBody ManualCaseUpdateRequest request) {
        return ApiResponse.ok(manualCaseService.updateCase(caseId, request));
    }

    /**
     * 删除手动化用例
     */
    @PostMapping("/{caseId}/delete")
    public ApiResponse<Void> delete(@PathVariable Long projectId,
                                     @PathVariable Long caseId) {
        manualCaseService.deleteCase(caseId);
        return ApiResponse.ok();
    }

    /**
     * 启用/废弃手动化用例（targetStatus 为空时按当前值取反）
     */
    @PostMapping("/{caseId}/status")
    public ApiResponse<ManualCaseResponse> toggleStatus(@PathVariable Long projectId,
                                                         @PathVariable Long caseId,
                                                         @RequestParam(required = false) Integer targetStatus) {
        return ApiResponse.ok(manualCaseService.toggleStatus(caseId, targetStatus));
    }

    // ───────────── 附件 ─────────────

    /**
     * 添加附件记录
     */
    @PostMapping("/{caseId}/attachments")
    public ApiResponse<ManualCaseAttachmentResponse> addAttachment(@PathVariable Long projectId,
                                                                    @PathVariable Long caseId,
                                                                    @RequestParam String fileName,
                                                                    @RequestParam String fileUrl,
                                                                    @RequestParam(required = false) Long fileSize) {
        return ApiResponse.ok(manualCaseService.addAttachment(projectId, caseId, fileName, fileUrl, fileSize));
    }

    /**
     * 删除附件
     */
    @PostMapping("/{caseId}/attachments/{attachmentId}/delete")
    public ApiResponse<Void> deleteAttachment(@PathVariable Long projectId,
                                               @PathVariable Long caseId,
                                               @PathVariable Long attachmentId) {
        manualCaseService.deleteAttachment(projectId, caseId, attachmentId);
        return ApiResponse.ok();
    }
}
