/**
 * @author HXN
 * @date 2026-09-15
 * @description 文档分块实体
 */
package com.platform.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文档分块实体
 *
 * <p>对应数据库 knowledge_chunk 表。存储文档解析后的文本块及其向量化状态。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_chunk")
public class KnowledgeChunk extends BaseEntity {

    /**
     * 所属文档 ID
     */
    private Long documentId;

    /**
     * 分块序号（从 0 开始）
     */
    private Integer chunkIndex;

    /**
     * 分块文本内容
     */
    private String content;

    /**
     * 预估 token 数
     */
    private Integer tokenCount;

    /**
     * 元数据（JSON，如页码、标题等）
     */
    private String metadataJson;

    /**
     * 向量 ID（Qdrant point ID）
     */
    private String vectorId;

    /**
     * 状态：PENDING/VECTORIZED/ERROR
     */
    private String status;
}
