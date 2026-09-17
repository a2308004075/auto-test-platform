/**
 * @author HXN
 * @date 2026-09-15
 * @description AI 白盒测试变更方法实体
 */
package com.platform.whitebox.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 白盒测试变更方法实体
 *
 * <p>对应数据库 ai_whitebox_method 表。仅含 created_at，无 updated_at。</p>
 */
@Data
@TableName("ai_whitebox_method")
public class AiWhiteboxMethod implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /**
     * 所属任务 ID
     */
    private Long taskId;

    /**
     * 源码文件相对路径
     */
    private String filePath;

    /**
     * 完整类名（含包名）
     */
    private String className;

    /**
     * 方法名
     */
    private String methodName;

    /**
     * 方法签名
     */
    private String methodSignature;

    /**
     * 变更类型：MODIFIED-直接变更，ADDED-新增，INDIRECT-间接影响
     */
    private String changeType;

    /**
     * 方法起始行号
     */
    private Integer startLine;

    /**
     * 方法结束行号
     */
    private Integer endLine;

    /**
     * 方法源码
     */
    private String sourceCode;

    /**
     * 静态分析上下文包（JSON：分支结构/Javadoc/依赖）
     */
    private String contextJson;

    /**
     * 关联需求条目（JSON 数组）
     */
    private String relatedRequirementsJson;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
