/**
 * @author HXN
 * @date 2026-09-15
 * @description 知识库文档实体
 */
package com.platform.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 知识库文档实体
 *
 * <p>对应数据库 knowledge_document 表。记录同步到知识库的资料元数据及处理状态。
 * 来源分五类（source_type）：需求文档版本（REQUIREMENT_VERSION）、项目文档（PROJECT_DOC）、
 * 源代码仓库（CODE_REPOSITORY）、接口文档模块（API_MODULE）、界面元素仓库（UI_ELEMENT）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_document")
public class KnowledgeDocument extends BaseEntity {

    /**
     * 所属知识库 ID
     */
    private Long knowledgeBaseId;

    /**
     * 关联项目资料文档 ID（历史字段，source_type/source_id 的前身，仅 PROJECT_DOC 来源使用）
     */
    private Long projectDocId;

    /**
     * 来源类型：REQUIREMENT_VERSION/PROJECT_DOC/CODE_REPOSITORY/API_MODULE/UI_ELEMENT
     */
    private String sourceType;

    /**
     * 来源记录 ID（对应来源表主键：需求版本 ID/项目文档 ID/仓库 ID/接口模块 ID/仓库 ID）
     */
    private Long sourceId;

    /**
     * 文档名称
     */
    private String docName;

    /**
     * 原始文件名
     */
    private String fileName;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * MIME 类型
     */
    private String contentType;

    /**
     * 处理状态：PENDING/PROCESSING/COMPLETED/ERROR
     */
    private String status;

    /**
     * 分块数
     */
    private Integer chunkCount;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 处理配置（JSON）
     */
    private String processConfig;

    /**
     * 内容指纹（MD5），用于增量同步判断内容是否变化、避免重复向量化
     */
    private String contentHash;

    /**
     * 导入人 ID
     */
    private Long createdBy;
}
