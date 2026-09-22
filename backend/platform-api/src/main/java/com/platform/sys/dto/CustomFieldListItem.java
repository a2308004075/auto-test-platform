/**
 * @author HXN
 * @date 2026-08-30
 * @description 自定义字段列表项响应 DTO
 */
package com.platform.sys.dto;

import lombok.Data;

/**
 * 自定义字段列表项（管理页面展示用）
 */
@Data
public class CustomFieldListItem {

    private Long id;
    private Long projectId;
    private String module;
    private String viewType;
    private String fieldKey;
    private String fieldLabel;
    private String description;
    private String fieldType;
    private String optionsJson;
    private String defaultValue;
    private Integer isRequired;
    private String displayScope;
    private Integer sortNo;
    private Integer isActive;
    private String createdAt;
    private String updatedAt;
}
