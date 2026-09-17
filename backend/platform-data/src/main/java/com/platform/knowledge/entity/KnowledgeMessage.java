/**
 * @author HXN
 * @date 2026-09-15
 * @description 知识库对话消息实体
 */
package com.platform.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识库对话消息实体
 *
 * <p>对应数据库 knowledge_message 表。存储对话中的每条消息（用户提问/AI回答/系统消息）。</p>
 */
@Data
@TableName("knowledge_message")
public class KnowledgeMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 所属会话 ID
     */
    private Long conversationId;

    /**
     * 角色：user/assistant/system
     */
    private String role;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 消耗 token 数
     */
    private Integer tokensUsed;

    /**
     * 引用来源（JSON 数组）
     */
    private String sourcesJson;

    /**
     * 生成耗时（毫秒）
     */
    private Long durationMs;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
