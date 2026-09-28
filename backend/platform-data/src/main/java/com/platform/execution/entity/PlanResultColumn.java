/**
 * @author HXN
 * @date 2026-09-28
 * @description 计划级自定义测试结果列实体类
 */
package com.platform.execution.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 计划级自定义测试结果列实体
 *
 * <p>对应数据库 plan_result_column 表。手动计划执行页的"多轮结果列"定义
 * （如：第一次台架测试结果、第一次整站测试结果），挂在计划级、历次执行共享；
 * 执行完成时按快照写入 test_execution.result_columns，归档记录展示不受
 * 后续列改名/删除影响。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("plan_result_column")
public class PlanResultColumn extends BaseEntity {

    /**
     * 所属测试计划 ID
     */
    private Long planId;

    /**
     * 列名称（用户自定义，计划内唯一）
     */
    private String columnName;

    /**
     * 列顺序（添加时取当前计划最大值+1，上移/下移相邻交换）
     */
    private Integer sortNo;

    /**
     * 创建人 ID
     */
    private Long createdBy;
}
