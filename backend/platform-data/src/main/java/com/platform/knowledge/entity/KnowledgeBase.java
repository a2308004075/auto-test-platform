/**
 * @author HXN
 * @date 2026-09-15
 * @description 知识库主表实体
 */
package com.platform.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 知识库实体
 *
 * <p>对应数据库 knowledge_base 表。一个知识库属于一个项目，
 * 包含多个文档和对应的向量集合。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_base")
public class KnowledgeBase extends BaseEntity {

    /**
     * 所属项目 ID
     */
    private Long projectId;

    /**
     * 知识库名称
     */
    private String name;

    /**
     * 知识库描述
     */
    private String description;

    /**
     * 向量嵌入模型名称
     */
    private String embeddingModel;

    /**
     * 向量维度
     */
    private Integer embeddingDimension;

    /**
     * 文档总数
     */
    private Integer docCount;

    /**
     * 分块总数
     */
    private Integer chunkCount;

    /**
     * 状态：READY/PROCESSING/ERROR
     */
    private String status;

    /**
     * 创建人 ID
     */
    private Long createdBy;
}
