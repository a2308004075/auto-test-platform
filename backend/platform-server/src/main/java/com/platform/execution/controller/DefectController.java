/**
 * @author HXN
 * @date 2026-08-30
 * @description 缺陷管理控制器
 */
package com.platform.execution.controller;

import com.platform.common.response.ApiResponse;
import com.platform.common.response.PageResponse;
import com.platform.execution.dto.*;
import com.platform.execution.service.DefectService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

/**
 * 缺陷管理接口
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/defects")
@RequiredArgsConstructor
public class DefectController {

    private final DefectService defectService;

    /**
     * 分页查询缺陷
     */
    @GetMapping
    public ApiResponse<PageResponse<DefectResponse>> list(@PathVariable Long projectId,
                                                           @RequestParam(required = false) Long groupId,
                                                           @RequestParam(required = false) String keyword,
                                                           @RequestParam(required = false) String status,
                                                           @RequestParam(required = false) String severity,
                                                           @RequestParam(required = false) Long assigneeId,
                                                           @RequestParam(defaultValue = "1") int page,
                                                           @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(defectService.listDefects(projectId, groupId, keyword, status, severity, assigneeId, page, pageSize));
    }

    /**
     * 创建缺陷
     */
    @PostMapping
    public ApiResponse<DefectResponse> create(@PathVariable Long projectId,
                                               @Valid @RequestBody DefectCreateRequest request) {
        return ApiResponse.ok(defectService.createDefect(projectId, request));
    }

    /**
     * 获取缺陷详情
     */
    @GetMapping("/{defectId}")
    public ApiResponse<DefectResponse> get(@PathVariable Long projectId,
                                            @PathVariable Long defectId) {
        return ApiResponse.ok(defectService.getDefect(defectId));
    }

    /**
     * 更新缺陷
     */
    @PostMapping("/{defectId}")
    public ApiResponse<DefectResponse> update(@PathVariable Long projectId,
                                               @PathVariable Long defectId,
                                               @Valid @RequestBody DefectUpdateRequest request) {
        return ApiResponse.ok(defectService.updateDefect(defectId, request));
    }

    /**
     * 删除缺陷
     */
    @PostMapping("/{defectId}/delete")
    public ApiResponse<Void> delete(@PathVariable Long projectId,
                                     @PathVariable Long defectId) {
        defectService.deleteDefect(defectId);
        return ApiResponse.ok();
    }

    /**
     * 状态流转
     */
    @PostMapping("/{defectId}/transition")
    public ApiResponse<DefectResponse> transition(@PathVariable Long projectId,
                                                   @PathVariable Long defectId,
                                                   @Valid @RequestBody DefectStatusTransitionRequest request) {
        return ApiResponse.ok(defectService.transitionStatus(defectId, request));
    }

    /**
     * 查询当前用户的待处理缺陷（我的任务）
     */
    @GetMapping("/my-tasks")
    public ApiResponse<List<DefectResponse>> myTasks(@PathVariable Long projectId,
                                                      @RequestParam(required = false) Long userId) {
        return ApiResponse.ok(defectService.listAssignedDefects(userId));
    }

    // ───────────── 关联 ─────────────

    /**
     * 添加关联
     */
    @PostMapping("/{defectId}/relations")
    public ApiResponse<DefectRelationResponse> addRelation(@PathVariable Long projectId,
                                                            @PathVariable Long defectId,
                                                            @Valid @RequestBody DefectRelationCreateRequest request) {
        return ApiResponse.ok(defectService.addRelation(defectId, request));
    }

    /**
     * 删除关联
     */
    @PostMapping("/{defectId}/relations/{relationId}/delete")
    public ApiResponse<Void> deleteRelation(@PathVariable Long projectId,
                                             @PathVariable Long defectId,
                                             @PathVariable Long relationId) {
        defectService.deleteRelation(defectId, relationId);
        return ApiResponse.ok();
    }

    /**
     * 按目标反查关联（用例视角：该用例被哪些缺陷关联）
     */
    @GetMapping("/by-target")
    public ApiResponse<List<DefectRelationResponse>> listByTarget(@PathVariable Long projectId,
                                                                   @RequestParam String targetType,
                                                                   @RequestParam Long targetId) {
        return ApiResponse.ok(defectService.listRelationsByTarget(projectId, targetType, targetId));
    }

    // ───────────── 附件 ─────────────

    /**
     * 添加附件记录
     */
    @PostMapping("/{defectId}/attachments")
    public ApiResponse<DefectAttachmentResponse> addAttachment(@PathVariable Long projectId,
                                                                @PathVariable Long defectId,
                                                                @RequestParam String fileName,
                                                                @RequestParam String fileUrl,
                                                                @RequestParam(required = false) Long fileSize) {
        return ApiResponse.ok(defectService.addAttachment(defectId, fileName, fileUrl, fileSize));
    }

    /**
     * 删除附件
     */
    @PostMapping("/{defectId}/attachments/{attachmentId}/delete")
    public ApiResponse<Void> deleteAttachment(@PathVariable Long projectId,
                                               @PathVariable Long defectId,
                                               @PathVariable Long attachmentId) {
        defectService.deleteAttachment(defectId, attachmentId);
        return ApiResponse.ok();
    }
}
