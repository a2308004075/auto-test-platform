/**
 * @author HXN
 * @date 2026-08-30
 * @description 需求条目创建/更新请求 DTO
 */
package com.platform.requirement.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Map;

/**
 * 创建/更新需求条目请求
 */
@Data
public class RequirementItemCreateRequest {

    private Long versionId;

    @NotBlank(message = "需求标题不能为空")
    @Size(max = 200, message = "需求标题长度不能超过 200")
    private String title;

    private String description;

    private String reqType;

    private String priority;

    private String status;

    @Size(max = 50, message = "负责人长度不能超过 50")
    private String assignee;

    private LocalDate deadline;

    /**
     * 自定义字段值（fieldKey -> 字段值，由【字段管理】动态配置驱动）
     */
    private Map<String, String> customFields;
}
