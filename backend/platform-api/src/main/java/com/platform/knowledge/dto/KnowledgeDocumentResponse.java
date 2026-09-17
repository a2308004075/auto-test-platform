/**
 * @author HXN
 * @date 2026-09-15
 * @description 知识库文档响应
 */
package com.platform.knowledge.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KnowledgeDocumentResponse {

    private Long id;
    private Long knowledgeBaseId;
    private Long projectDocId;

    /**
     * 来源类型：REQUIREMENT_VERSION/PROJECT_DOC/CODE_REPOSITORY/API_MODULE/UI_ELEMENT
     */
    private String sourceType;

    /**
     * 来源记录 ID
     */
    private Long sourceId;

    private String docName;
    private String fileName;
    private Long fileSize;
    private String contentType;
    private String status;
    private Integer chunkCount;
    private String errorMessage;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
