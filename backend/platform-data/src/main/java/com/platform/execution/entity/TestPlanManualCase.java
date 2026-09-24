/**
 * @author HXN
 * @date 2026-09-24
 * @description 测试计划-手动化用例关联实体类
 */
package com.platform.execution.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 测试计划-手动化用例关联实体
 *
 * <p>对应数据库 test_plan_manual_case 表。承载"计划-用例"关联（权威读源），
 * 并作为关联级动态字段值（sys_custom_field_value，module='plan_case'）的挂载实体：
 * 同一用例在不同计划中可独立设置"台架是否执行/整站是否执行"等字段。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("test_plan_manual_case")
public class TestPlanManualCase extends BaseEntity {

    /**
     * 测试计划 ID
     */
    private Long planId;

    /**
     * 手动化用例 ID
     */
    private Long manualCaseId;

    /**
     * 计划内的用例顺序（沿用原 JSON 数组顺序）
     */
    private Integer sortNo;
}
