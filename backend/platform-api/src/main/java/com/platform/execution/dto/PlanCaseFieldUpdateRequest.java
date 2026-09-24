/**
 * @author HXN
 * @date 2026-09-24
 * @description 测试计划关联用例动态字段值更新请求 DTO
 */
package com.platform.execution.dto;

import lombok.Data;

import java.util.Map;

/**
 * 测试计划关联用例动态字段值更新请求
 *
 * <p>用于计划详情页"关联手动化用例"表格行内即时保存关联级动态字段
 * （如台架是否执行/整站是否执行），值按 fieldKey 全量覆盖式提交。
 */
@Data
public class PlanCaseFieldUpdateRequest {

    /**
     * 字段值（fieldKey -> value，字符串形式；空值跳过）
     */
    private Map<String, String> fieldValues;
}
