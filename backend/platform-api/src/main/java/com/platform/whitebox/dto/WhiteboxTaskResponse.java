/**
 * @author HXN
 * @date 2026-09-15
 * @description AI 白盒测试任务响应 DTO
 */
package com.platform.whitebox.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 白盒测试任务响应
 */
@Data
public class WhiteboxTaskResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long projectId;
    private Long repositoryId;
    private String repositoryName;
    private Long requirementVersionId;
    private String requirementVersionName;
    private String baselineCommit;
    private String headCommit;
    private String status;
    private String currentPhase;
    private Integer progress;
    private String taskLog;
    private Integer totalChangedFiles;
    private Integer totalChangedMethods;
    private Integer totalCases;
    private Integer totalTestClasses;
    private Integer compilePass;
    private Integer execPass;
    private Integer execFail;
    private Integer totalMutants;
    private Integer killedMutants;
    private BigDecimal branchCoverage;
    private BigDecimal lineCoverage;
    private BigDecimal mutationScore;
    private Long tokensUsed;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
