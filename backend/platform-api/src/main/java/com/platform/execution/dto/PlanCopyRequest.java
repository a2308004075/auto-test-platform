/**
 * @author HXN
 * @date 2026-09-28
 * @description 测试计划复制请求 DTO
 */
package com.platform.execution.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 复制测试计划请求
 *
 * <p>基础信息（描述/类型/环境/触发策略/启停）与关联内容（手动用例/自动化套件）
 * 随源计划复制；名称与所属分组由请求指定；执行记录与结果列定义不复制。
 */
@Data
public class PlanCopyRequest {

    /**
     * 新计划名称（项目内唯一）
     */
    @NotBlank(message = "计划名称不能为空")
    private String name;

    /**
     * 新计划所属分组 ID（null=未分组）
     */
    private Long groupId;
}
