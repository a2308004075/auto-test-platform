/**
 * @author HXN
 * @date 2026-08-20 15:34
 * @description TestResult 响应 DTO
 */
package com.platform.execution.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 测试结果明细响应
 */
@Data
public class TestResultResponse {

    private Long id;

    private Long executionId;

    private Long autoCaseId;

    private Long manualCaseId;

    /**
     * 用例类型：AUTO / MANUAL
     */
    private String caseType;

    /**
     * 用例名称
     */
    private String caseName;

    /**
     * 用例执行结果：PASSED / FAILED / SKIPPED / ERROR / PENDING
     */
    private String status;

    private String actualResult;

    private String expectedResult;

    private String errorMessage;

    /**
     * 执行日志（JSON）
     */
    private String logs;

    private Integer durationMs;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    /**
     * 多轮测试结果：结果列 ID → {status, remark}
     *
     * <p>仅手动计划执行页填写的单元格数据；归档记录的列名
     * 由执行单 resultColumns 快照提供。
     */
    private Map<String, RoundCell> roundResults;
}
