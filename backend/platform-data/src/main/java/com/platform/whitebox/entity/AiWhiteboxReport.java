/**
 * @author HXN
 * @date 2026-09-15
 * @description AI 白盒测试报告实体
 */
package com.platform.whitebox.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 白盒测试报告实体
 *
 * <p>对应数据库 ai_whitebox_report 表。仅含 created_at，无 updated_at。</p>
 */
@Data
@TableName("ai_whitebox_report")
public class AiWhiteboxReport implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /**
     * 所属任务 ID
     */
    private Long taskId;

    /**
     * 报告格式：markdown/json
     */
    private String reportFormat;

    /**
     * 报告内容
     */
    private String reportContent;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
