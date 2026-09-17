/**
 * @author HXN
 * @date 2026-09-15
 * @description 对话消息响应
 */
package com.platform.knowledge.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ChatMessageResponse {

    private Long id;
    private Long conversationId;
    private String role;
    private String content;
    private Integer tokensUsed;
    private List<SourceItem> sources;
    private Long durationMs;
    private LocalDateTime createdAt;

    /**
     * 引用来源项
     */
    @Data
    public static class SourceItem {
        /** 分块 ID */
        private Long chunkId;
        /** 文档名称 */
        private String docName;
        /** 分块序号 */
        private Integer chunkIndex;
        /** 分块内容摘要 */
        private String content;
        /** 相似度分数 */
        private Double score;
    }
}
