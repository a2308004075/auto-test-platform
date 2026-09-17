/**
 * @author HXN
 * @date 2026-09-15
 * @description AI 白盒测试生成测试响应 DTO
 */
package com.platform.whitebox.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 白盒测试生成测试响应
 */
@Data
public class WhiteboxTestResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long taskId;
    private Long methodId;
    private String testClassName;
    private String testMethodName;
    private String caseTitle;
    private String caseType;
    private String priority;
    private String preconditions;
    private String operationSteps;
    private String expectedResult;
    private String relatedRequirementIds;
    private String testCode;
    private String compileStatus;
    private String execStatus;
    private String execMessage;
    private Integer savedToManual;
    private Long manualCaseId;
    private LocalDateTime createdAt;
}
