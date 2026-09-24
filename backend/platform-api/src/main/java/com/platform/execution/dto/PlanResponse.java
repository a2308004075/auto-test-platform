/**
 * @author HXN
 * @date 2026-08-20 15:34
 * @description 测试计划响应 DTO
 */
package com.platform.execution.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 测试计划响应
 */
@Data
public class PlanResponse {

    private Long id;

    private Long projectId;

    private String name;

    private String description;

    /**
     * 计划类型：MANUAL-手动测试计划，AUTO-自动测试计划
     */
    private String planType;

    /**
     * 所属分组 ID
     */
    private Long groupId;

    /**
     * 关联的自动化套件 ID 列表
     */
    private List<Long> autoSuiteIds;

    /**
     * 关联的自动化套件名称列表（表格展示用）
     */
    private List<String> autoSuiteNames;

    /**
     * 关联的手动化用例 ID 列表
     */
    private List<Long> manualCaseIds;

    /**
     * 关联的手动化用例名称列表（表格展示用）
     */
    private List<String> manualCaseNames;

    private Long environmentId;

    private String environmentName;

    private String scheduleCron;

    /**
     * 触发方式：MANUAL / SCHEDULED / CI
     */
    private String triggerType;

    private Integer isActive;

    private Long createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /**
     * 最近一次执行时间（从 test_execution 聚合）
     */
    private LocalDateTime lastExecutionTime;

    /**
     * 最近一次执行通过率（0-100，保留 1 位小数）
     */
    private Double passRate;

    /**
     * 关联自动化套件下的自动化用例总数
     */
    private Integer caseCount;

    /**
     * 关联的手动化用例数量
     */
    private Integer manualCaseCount;

    /**
     * 关联的手动化用例明细列表（详情页关联用例表格展示用）
     */
    private List<ManualCaseBriefDTO> manualCaseDetails;

    /**
     * 关联的自动化套件明细列表（详情页关联套件表格展示用）
     */
    private List<AutoSuiteBriefDTO> autoSuiteDetails;
}
