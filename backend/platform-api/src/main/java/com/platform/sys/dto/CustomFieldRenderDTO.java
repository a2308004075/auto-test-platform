/**
 * @author HXN
 * @date 2026-08-30
 * @description 自定义字段渲染 DTO（前端编辑页渲染用）
 */
package com.platform.sys.dto;

import lombok.Data;

import java.util.List;

/**
 * 自定义字段渲染 DTO（精简结构，不含审计字段）
 * <p>options 为后端统一组装好的选项列表（select 类型来自 optionsJson，
 * user/environment 类型由服务层从对应数据源查询），前端直接渲染
 */
@Data
public class CustomFieldRenderDTO {

    private Long id;
    private String fieldKey;
    private String fieldLabel;
    private String fieldType;
    private String optionsJson;
    /**
     * 统一选项列表（所有下拉类字段均有值）
     */
    private List<FieldOption> options;
    private String defaultValue;
    private Integer isRequired;
    private Integer sortNo;

    /**
     * 字段选项（label/value 对）
     */
    @Data
    public static class FieldOption {
        private String label;
        private String value;

        public FieldOption() {
        }

        public FieldOption(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }
}
