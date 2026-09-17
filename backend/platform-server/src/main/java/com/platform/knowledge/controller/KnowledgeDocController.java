/**
 * @author HXN
 * @date 2026-09-15
 * @description 知识库文档管理控制器
 */
package com.platform.knowledge.controller;

import com.platform.common.response.ApiResponse;
import com.platform.knowledge.dto.KnowledgeDocumentResponse;
import com.platform.knowledge.service.KnowledgeDocService;
import com.platform.knowledge.service.KnowledgeSyncService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 知识库文档管理控制器
 *
 * <p>接口路径：{@code /api/v1/projects/{projectId}/knowledge/{kbId}/documents}。
 * 文档由五类项目资料自动同步产生，不再提供手动导入入口。</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/knowledge/{kbId}/documents")
public class KnowledgeDocController {

    private final KnowledgeDocService docService;
    private final KnowledgeSyncService syncService;

    public KnowledgeDocController(KnowledgeDocService docService,
                                  KnowledgeSyncService syncService) {
        this.docService = docService;
        this.syncService = syncService;
    }

    /**
     * 手动触发全量同步：采集项目下五类资料写入知识库
     *
     * <p>资料变更平时由 {@link KnowledgeSyncService} 事件自动同步，
     * 本接口用于补历史数据或自动同步失败后的手动修复。</p>
     */
    @PostMapping("/sync")
    @PreAuthorize("hasAuthority('project:knowledge')")
    public ApiResponse<Void> sync(@PathVariable Long projectId,
                                  @PathVariable Long kbId) {
        syncService.syncAllMaterials(projectId, kbId);
        return ApiResponse.ok();
    }

    /**
     * 查询知识库下的文档列表
     */
    @GetMapping
    @PreAuthorize("hasAuthority('project:knowledge')")
    public ApiResponse<List<KnowledgeDocumentResponse>> listDocuments(@PathVariable Long projectId,
                                                                        @PathVariable Long kbId) {
        return ApiResponse.ok(docService.listDocuments(kbId));
    }

    /**
     * 删除知识库文档
     */
    @DeleteMapping("/{docId}")
    @PreAuthorize("hasAuthority('project:knowledge:doc')")
    public ApiResponse<Void> deleteDocument(@PathVariable Long projectId,
                                              @PathVariable Long kbId,
                                              @PathVariable Long docId) {
        docService.deleteDocument(kbId, docId);
        return ApiResponse.ok();
    }
}
