/**
 * @author HXN
 * @date 2026-08-20 15:34
 * @description 测试结果实体类
 */
package com.platform.execution.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 测试结果明细实体
 *
 * <p>对应数据库 test_result 表。该表无 created_at/updated_at 字段，不继承 BaseEntity。
 */
@Data
@TableName("test_result")
public class TestResult implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long executionId;

    /**
     * 所属自动化用例 ID（case_type=AUTO 时有值）
     */
    private Long autoCaseId;

    /**
     * 所属手动化用例 ID（case_type=MANUAL 时有值）
     */
    private Long manualCaseId;

    /**
     * 用例类型：AUTO / MANUAL
     */
    private String caseType;

    /**
     * 用例执行结果：PASSED / FAILED / SKIPPED / ERROR / PENDING
     */
    private String status;

    /**
     * 实际结果摘要
     */
    private String actualResult;

    /**
     * 预期结果摘要
     */
    private String expectedResult;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 执行日志（JSON，每步骤 req/res 详情）
     */
    private String logs;

    /**
     * 执行耗时（毫秒）
     */
    private Integer durationMs;

    /**
     * 多轮测试结果单元格值（JSON：{"<结果列ID>":{"status":"PASSED|FAILED|SKIPPED","remark":"备注"}}）
     *
     * <p>手动计划执行页的“用例行 × 自定义结果列”单元格数据；
     * 列 ID 对应 plan_result_column.id，执行完成归档后随列定义快照稳定回放。
     */
    private String roundResults;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}
