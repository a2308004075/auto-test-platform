/**
 * @author HXN
 * @date 2026-08-30
 * @description 自定义字段定义实体类
 */
package com.platform.sys.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 自定义字段定义实体
 *
 * <p>按项目、模块、视图独立配置动态字段
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_custom_field")
public class CustomField extends BaseEntity {

    /**
     * 所属项目 ID
     */
    private Long projectId;

    /**
     * 模块标识：defect / requirement
     */
    private String module;

    /**
     * 视图：create / edit
     */
    private String viewType;

    /**
     * 字段唯一标识（如 severity, assignee_id）
     */
    private String fieldKey;

    /**
     * 显示标签
     */
    private String fieldLabel;

    /**
     * 字段描述（最多 200 字）
     */
    private String description;

    /**
     * 字段类型：text / textarea / select / datetime / number / user / environment
     */
    private String fieldType;

    /**
     * 数据源编码（历史遗留列：原 dict 类型使用，现已移除字典类型，恒为空）
     */
    private String sourceCode;

    /**
     * 下拉框选项 JSON: [{"label":"高","value":"HIGH"},...]
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
     * 排序号（升序）
     */
    private Integer sortNo;

    /**
     * 是否启用（1=启用 0=停用）
     */
    @TableLogic
    private Integer isActive;
}
