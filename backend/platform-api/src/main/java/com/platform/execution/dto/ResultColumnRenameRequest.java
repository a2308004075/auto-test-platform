/**
 * @author HXN
 * @date 2026-09-28
 * @description 自定义测试结果列改名请求 DTO
 */
package com.platform.execution.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 自定义测试结果列改名请求
 */
@Data
public class ResultColumnRenameRequest {

    /**
     * 新列名称
     */
    @NotBlank(message = "列名称不能为空")
    @Size(max = 100, message = "列名称长度不能超过 100 字符")
    private String columnName;
}
