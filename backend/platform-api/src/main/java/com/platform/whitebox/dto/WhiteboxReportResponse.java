/**
 * @author HXN
 * @date 2026-09-15
 * @description AI 白盒测试报告响应 DTO
 */
package com.platform.whitebox.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 白盒测试报告响应
 */
@Data
public class WhiteboxReportResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long taskId;
    private String reportFormat;
    private String reportContent;
    private String fileName;
    private Long fileSize;
    private LocalDateTime createdAt;
}
