/**
 * @author HXN
 * @date 2026-08-20 15:34
 * @description 执行记录实体类
 */
package com.platform.execution.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 测试执行记录实体
 *
 * <p>对应数据库 test_execution 表。该表无 updated_at 字段，不继承 BaseEntity。
 */
@Data
@TableName("test_execution")
public class TestExecution implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long planId;

    private Long environmentId;

    /**
     * 触发方式：MANUAL / SCHEDULED / CI
     */
    private String triggerType;

    /**
     * 执行状态：PENDING / RUNNING / COMPLETED / FAILED / CANCELLED
     */
    private String status;

    /**
     * 执行完成时的结果列定义快照（JSON：[{"id":1,"columnName":"第一次台架测试结果","sortNo":1}]）
     *
     * <p>仅手动计划在【执行完成】时写入，测试记录详情页据此脱离
     * plan_result_column 稳定回放历次归档记录。
     */
    private String resultColumns;

    private Integer totalCases;

    private Integer passedCases;

    private Integer failedCases;

    private Integer skippedCases;

    /**
     * 总耗时（毫秒）
     */
    private Integer durationMs;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private Long triggeredBy;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
