/**
 * @author HXN
 * @date 2026-09-15
 * @description 知识库对话会话实体
 */
package com.platform.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 知识库对话会话实体
 *
 * <p>对应数据库 knowledge_conversation 表。记录用户与知识库的对话会话。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_conversation")
public class KnowledgeConversation extends BaseEntity {

    /**
     * 所属知识库 ID
     */
    private Long knowledgeBaseId;

    /**
     * 会话标题（自动取首条消息摘要）
     */
    private String title;

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 消息总数
     */
    private Integer messageCount;
}
