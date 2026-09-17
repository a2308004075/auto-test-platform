/**
 * @author HXN
 * @date 2026-09-15
 * @description 知识库管理控制器
 */
package com.platform.knowledge.controller;

import com.platform.common.response.ApiResponse;
import com.platform.knowledge.dto.KnowledgeBaseCreateRequest;
import com.platform.knowledge.dto.KnowledgeBaseResponse;
import com.platform.knowledge.service.KnowledgeBaseService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

/**
 * 知识库管理控制器
 *
 * <p>所有接口挂载在项目维度下：{@code /api/v1/projects/{projectId}/knowledge}</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/knowledge")
public class KnowledgeController {

    private final KnowledgeBaseService kbService;

    public KnowledgeController(KnowledgeBaseService kbService) {
        this.kbService = kbService;
    }

    /**
     * 查询项目下的知识库列表
     */
    @GetMapping
    @PreAuthorize("hasAuthority('project:knowledge')")
    public ApiResponse<List<KnowledgeBaseResponse>> list(@PathVariable Long projectId) {
        return ApiResponse.ok(kbService.list(projectId));
    }

    /**
     * 查询知识库详情
     */
    @GetMapping("/{kbId}")
    @PreAuthorize("hasAuthority('project:knowledge')")
    public ApiResponse<KnowledgeBaseResponse> getById(@PathVariable Long projectId,
                                                        @PathVariable Long kbId) {
        return ApiResponse.ok(kbService.getById(projectId, kbId));
    }

    /**
     * 获取项目默认知识库（智能问答直达入口）
     *
     * <p>存在则返回项目下最早创建的知识库，不存在则自动创建并触发全量资料同步。</p>
     */
    @GetMapping("/default")
    @PreAuthorize("hasAuthority('project:knowledge')")
    public ApiResponse<KnowledgeBaseResponse> getOrCreateDefault(@PathVariable Long projectId) {
        return ApiResponse.ok(kbService.getOrCreateDefault(projectId));
    }

    /**
     * 创建知识库
     */
    @PostMapping
    @PreAuthorize("hasAuthority('project:knowledge:doc')")
    public ApiResponse<KnowledgeBaseResponse> create(@PathVariable Long projectId,
                                                       @Valid @RequestBody KnowledgeBaseCreateRequest request) {
        return ApiResponse.ok(kbService.create(projectId, request));
    }

    /**
     * 删除知识库
     */
    @DeleteMapping("/{kbId}")
    @PreAuthorize("hasAuthority('project:knowledge:doc')")
    public ApiResponse<Void> delete(@PathVariable Long projectId,
                                      @PathVariable Long kbId) {
        kbService.delete(projectId, kbId);
        return ApiResponse.ok();
    }
}
