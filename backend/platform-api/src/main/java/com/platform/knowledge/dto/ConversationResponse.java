/**
 * @author HXN
 * @date 2026-09-15
 * @description 对话会话响应
 */
package com.platform.knowledge.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ConversationResponse {

    private Long id;
    private Long knowledgeBaseId;
    private String title;
    private Long userId;
    private Integer messageCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
