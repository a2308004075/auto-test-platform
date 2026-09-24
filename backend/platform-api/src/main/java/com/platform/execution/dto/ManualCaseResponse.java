/**
 * @author HXN
 * @date 2026-08-30
 * @description 手动化用例响应 DTO
 */
package com.platform.execution.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 手动化用例响应
 *
 * <p>caseType/priority/runInTestEnv/runInProdEnv 为存量列（已停止读写）保留字段，
 * 动态列展示以 customFields 为准（由【页面配置-手动用例字段】驱动）。</p>
 */
@Data
public class ManualCaseResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long projectId;
    private Long groupId;
    private String title;
    private String content;

    /**
     * 存量列（停止读写），动态值见 customFields.case_type
     */
    private String caseType;

    /**
     * 存量列（停止读写），动态值见 customFields.priority
     */
    private String priority;

    /**
     * 存量列（停止读写），动态值见 customFields.run_in_test_env
     */
    private Integer runInTestEnv;

    /**
     * 存量列（停止读写），动态值见 customFields.run_in_prod_env
     */
    private Integer runInProdEnv;

    /**
     * 用例状态（1-使用，0-废弃），值仍走 manual_case.case_status 列
     */
    private Integer caseStatus;

    private Long createdBy;
    private String createdByName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 动态字段值（fieldKey -&gt; 值），由【页面配置】驱动
     */
    private Map<String, String> customFields;

    /**
     * 附件列表（仅详情返回）
     */
    private List<ManualCaseAttachmentResponse> attachments;
}
