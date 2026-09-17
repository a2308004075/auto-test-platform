/**
 * @author HXN
 * @date 2026-09-15
 * @description AI 白盒测试变更方法响应 DTO
 */
package com.platform.whitebox.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 白盒测试变更方法响应
 */
@Data
public class WhiteboxMethodResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long taskId;
    private String filePath;
    private String className;
    private String methodName;
    private String methodSignature;
    private String changeType;
    private Integer startLine;
    private Integer endLine;
    private String sourceCode;
    private String contextJson;
    private String relatedRequirementsJson;
    private LocalDateTime createdAt;
}
