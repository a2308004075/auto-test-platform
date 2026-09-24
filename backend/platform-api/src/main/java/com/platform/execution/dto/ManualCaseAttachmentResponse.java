/**
 * @author HXN
 * @date 2026-09-22
 * @description 手动用例附件响应 DTO
 */
package com.platform.execution.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 手动用例附件响应
 */
@Data
public class ManualCaseAttachmentResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long manualCaseId;
    private String fileName;
    private String fileUrl;
    private Long fileSize;
    private Long createdBy;
    private String createdByName;
    private LocalDateTime createdAt;
}
