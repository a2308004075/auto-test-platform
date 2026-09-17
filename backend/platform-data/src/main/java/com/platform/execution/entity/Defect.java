/**
 * @author HXN
 * @date 2026-08-30
 * @description 缺陷实体类
 */
package com.platform.execution.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 缺陷实体
 *
 * <p>对应数据库 defect 表。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("defect")
public class Defect extends BaseEntity {

    /**
     * 所属项目 ID
     */
    private Long projectId;

    /**
     * 所属分组 ID
     */
    private Long groupId;

    /**
     * 缺陷编号
     */
    private String defectNo;

    /**
     * 缺陷标题
     */
    private String title;

    /**
     * 内容（富文本 HTML）
     */
    private String content;

    /**
     * 负责人 ID
     */
    private Long assigneeId;

    /**
     * 计划完成时间
     */
    private LocalDate dueDate;

    /**
     * 发现的版本
     */
    private String foundVersion;

    /**
     * 所属模块
     */
    private String moduleName;

    /**
     * 严重级别
     */
    private String severity;

    /**
     * 缺陷根源
     */
    private String source;

    /**
     * 环境 ID
     */
    private Long environmentId;

    /**
     * 原因描述
     */
    private String reasonDescription;

    /**
     * 责任人 ID
     */
    private Long responsibleId;

    /**
     * 重新打开次数
     */
    private Integer reopenCount;

    /**
     * 修改的版本
     */
    private String fixedVersion;

    /**
     * 计划提测时间
     */
    private LocalDate planTestDate;

    /**
     * 状态：NEW-新建/TO_CONFIRM-待确认/FIXING-修复中/TO_DEPLOY-待部署/PENDING-待验证/COMPLETED-已修复/REOPENED-重新打开/DEFERRED-延期修复/CLOSED-无需修复
     */
    private String status;

    /**
     * 父缺陷 ID（层级关系）
     */
    private Long parentId;

    /**
     * 总估算工时
     */
    private BigDecimal estimatedHours;

    /**
     * 总实际工时
     */
    private BigDecimal actualHours;

    /**
     * 总剩余工时
     */
    private BigDecimal remainingHours;

    /**
     * 创建人 ID
     */
    private Long createdBy;

    /**
     * 更新人 ID
     */
    @TableField("updated_by")
    private Long updatedBy;
}
