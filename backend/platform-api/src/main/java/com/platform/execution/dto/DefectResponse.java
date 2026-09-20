/**
 * @author HXN
 * @date 2026-08-30
 * @description 缺陷响应 DTO
 */
package com.platform.execution.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 缺陷响应
 */
@Data
public class DefectResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long projectId;
    private Long groupId;
    private String groupName;
    private String defectNo;
    private String title;
    private String content;

    private Long assigneeId;
    private String assigneeName;

    private LocalDate dueDate;
    private String foundVersion;
    private String moduleName;
    private String severity;
    private String source;

    private Long environmentId;
    private String environmentName;

    private String reasonDescription;

    private Long responsibleId;
    private String responsibleName;

    private Integer reopenCount;
    private String fixedVersion;
    private LocalDate planTestDate;
    private String status;

    private Long createdBy;
    private String createdByName;
    private Long updatedBy;
    private String updatedByName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 自定义字段值（fieldKey -> 值；详情与列表接口均返回，由【字段管理】动态配置驱动）
     */
    private Map<String, String> customFields;

    /**
     * 关联记录
     */
    private List<DefectRelationResponse> relations;

    /**
     * 附件列表
     */
    private List<DefectAttachmentResponse> attachments;

    /**
     * 变更记录
     */
    private List<DefectHistoryResponse> histories;
}
