/**
 * @author HXN
 * @date 2026-09-28
 * @description 手动计划执行页用例行响应 DTO
 */
package com.platform.execution.dto;

import lombok.Data;

import java.util.Map;

/**
 * 手动计划执行页的用例行响应
 *
 * <p>一行对应计划关联的一条手动化用例（及其预创建的 test_result 记录），
 * roundResults 为“结果列 ID → 单元格值”的多轮结果集合。
 */
@Data
public class ManualExecutionRowResponse {

    /**
     * 测试结果记录 ID（test_result.id，单元格更新的定位键）
     */
    private Long resultId;

    /**
     * 手动化用例 ID
     */
    private Long manualCaseId;

    /**
     * 用例标题
     */
    private String title;

    /**
     * 用例类型：NORMAL-正常，EXCEPTION-异常
     */
    private String caseType;

    /**
     * 优先级：高/中/低
     */
    private String priority;

    /**
     * 用例状态（1-使用，0-废弃）
     */
    private Integer caseStatus;

    /**
     * 多轮结果：结果列 ID → {status, remark}
     */
    private Map<String, RoundCell> roundResults;
}
