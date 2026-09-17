/**
 * @author HXN
 * @date 2026-09-15
 * @description 文档分块响应
 */
package com.platform.knowledge.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KnowledgeChunkResponse {

    private Long id;
    private Long documentId;
    private Integer chunkIndex;
    private String content;
    private Integer tokenCount;
    private String vectorId;
    private String status;
    private LocalDateTime createdAt;
}
