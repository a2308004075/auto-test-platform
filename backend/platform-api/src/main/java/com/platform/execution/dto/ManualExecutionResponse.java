/**
 * @author HXN
 * @date 2026-09-28
 * @description 手动计划执行页（执行工作台）响应 DTO
 */
package com.platform.execution.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 手动计划执行页响应
 *
 * <p>“获取或创建”进行中执行单（WAITING_MANUAL）后返回的完整工作台数据：
 * 执行单标识与状态 + 计划级结果列定义 + 用例行（含各格多轮结果）。
 */
@Data
public class ManualExecutionResponse {

    /**
     * 执行单 ID（进行中的测试结果记录单）
     */
    private Long executionId;

    private Long planId;

    private String planName;

    /**
     * 执行单状态：WAITING_MANUAL-进行中 / COMPLETED-已完成
     */
    private String status;

    /**
     * 计划关联用例数
     */
    private Integer totalCases;

    private LocalDateTime createdAt;

    /**
     * 计划级自定义结果列定义（历次执行共享，按 sortNo 升序）
     */
    private List<ResultColumnResponse> columns;

    /**
     * 用例行（每条关联手动化用例一行）
     */
    private List<ManualExecutionRowResponse> rows;
}
