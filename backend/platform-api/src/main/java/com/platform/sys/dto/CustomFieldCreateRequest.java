/**
 * @author HXN
 * @date 2026-08-30
 * @description 自定义字段创建/更新请求 DTO
 */
package com.platform.sys.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import java.util.List;

/**
 * 自定义字段创建/更新请求
 */
@Data
public class CustomFieldCreateRequest {

    /**
     * 所属项目 ID
     */
    @NotNull(message = "项目 ID 不能为空")
    private Long projectId;

    /**
     * 模块标识：defect / requirement
     */
    @NotBlank(message = "模块标识不能为空")
    private String module;

    /**
     * 视图：create / edit
     */
    @NotBlank(message = "视图类型不能为空")
    private String viewType;

    /**
     * 显示标签（同一项目 + 模块 + 视图内不可重复）
     */
    @NotBlank(message = "字段标签不能为空")
    private String fieldLabel;

    /**
     * 字段描述（最多 200 字）
     */
    @Size(max = 200, message = "描述最多 200 字")
    private String description;

    /**
     * 字段类型：text / textarea / select / datetime / number / user / environment
     */
    @NotBlank(message = "字段类型不能为空")
    private String fieldType;

    /**
     * 下拉框选项 JSON
     */
    private String optionsJson;

    /**
     * 默认值
     */
    private String defaultValue;

    /**
     * 是否必填：1=是 0=否
     */
    private Integer isRequired;

    /**
     * 显示位置（多值）：create=新建显示 detail=详情(编辑)显示（缺省为都显示）
     */
    private List<String> displayScope;

    /**
     * 排序号
     */
    private Integer sortNo;
}
