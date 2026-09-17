/**
 * @author HXN
 * @date 2026-09-15
 * @description 知识库响应
 */
package com.platform.knowledge.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KnowledgeBaseResponse {

    private Long id;
    private Long projectId;
    private String name;
    private String description;
    private String embeddingModel;
    private Integer embeddingDimension;
    private Integer docCount;
    private Integer chunkCount;
    private String status;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
