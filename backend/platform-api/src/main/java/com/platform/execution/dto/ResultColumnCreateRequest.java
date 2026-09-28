/**
 * @author HXN
 * @date 2026-09-28
 * @description 自定义测试结果列创建请求 DTO
 */
package com.platform.execution.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 自定义测试结果列创建请求（挂在计划级，历次执行共享）
 */
@Data
public class ResultColumnCreateRequest {

    /**
     * 列名称（用户自定义，如：第一次台架测试结果）
     */
    @NotBlank(message = "列名称不能为空")
    @Size(max = 100, message = "列名称长度不能超过 100 字符")
    private String columnName;
}
