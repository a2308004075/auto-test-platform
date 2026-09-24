/**
 * @author HXN
 * @date 2026-09-24
 * @description 手动化用例简要信息 DTO（测试计划详情关联用例表格展示用）
 */
package com.platform.execution.dto;

import lombok.Data;

import java.util.Map;

/**
 * 手动化用例简要信息
 *
 * <p>用于测试计划详情页"关联手动化用例"表格展示，避免前端二次反查。
 */
@Data
public class ManualCaseBriefDTO {

    /**
     * 用例 ID
     */
    private Long id;

    /**
     * 用例标题
     */
    private String title;

    /**
     * 用例状态（1-使用，0-废弃）
     */
    private Integer caseStatus;

    /**
     * 计划-用例关联行 ID（test_plan_manual_case.id，关联级动态字段的挂载实体）
     */
    private Long relationId;

    /**
     * 关联级动态字段值（fieldKey -> value，如 run_on_bench/run_on_site，
     * 值存 sys_custom_field_value，module='plan_case'，entity_id=关联行 ID）
     */
    private Map<String, String> fieldValues;
}
