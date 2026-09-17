/**
 * @author HXN
 * @date 2026-09-15
 * @description AI 白盒测试任务实体
 */
package com.platform.whitebox.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 白盒测试任务实体
 *
 * <p>对应数据库 ai_whitebox_task 表。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_whitebox_task")
public class AiWhiteboxTask extends BaseEntity {

    /**
     * 所属项目 ID
     */
    private Long projectId;

    /**
     * 源代码仓库 ID（code_repository.id）
     */
    private Long repositoryId;

    /**
     * 仓库名称（冗余）
     */
    private String repositoryName;

    /**
     * 需求版本 ID（NULL=未选择，语义受限）
     */
    private Long requirementVersionId;

    /**
     * 需求版本名称（冗余）
     */
    private String requirementVersionName;

    /**
     * 基准 commit（增量对比起点）
     */
    private String baselineCommit;

    /**
     * 本次任务 HEAD commit
     */
    private String headCommit;

    /**
     * 状态：PENDING/RUNNING/COMPLETED/FAILED/CANCELLED
     */
    private String status;

    /**
     * 当前阶段：sync/diff/analyze/align/generate/build/test/quality/report
     */
    private String currentPhase;

    /**
     * 进度百分比 0-100
     */
    private Integer progress;

    /**
     * 任务日志
     */
    private String taskLog;

    /**
     * 变更文件总数
     */
    private Integer totalChangedFiles;

    /**
     * 变更方法总数
     */
    private Integer totalChangedMethods;

    /**
     * 生成用例总数
     */
    private Integer totalCases;

    /**
     * 生成测试类总数
     */
    private Integer totalTestClasses;

    /**
     * 编译通过测试类数
     */
    private Integer compilePass;

    /**
     * 执行通过用例数
     */
    private Integer execPass;

    /**
     * 执行失败用例数
     */
    private Integer execFail;

    /**
     * 变异体总数
     */
    private Integer totalMutants;

    /**
     * 被杀死变异体数
     */
    private Integer killedMutants;

    /**
     * 分支覆盖率（%）
     */
    private BigDecimal branchCoverage;

    /**
     * 行覆盖率（%）
     */
    private BigDecimal lineCoverage;

    /**
     * 变异得分（%）
     */
    private BigDecimal mutationScore;

    /**
     * LLM 消耗 token 总数
     */
    private Long tokensUsed;

    /**
     * 开始时间
     */
    private LocalDateTime startedAt;

    /**
     * 完成时间
     */
    private LocalDateTime completedAt;

    /**
     * 创建人 ID
     */
    private Long createdBy;
}
