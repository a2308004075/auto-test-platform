/**
 * @author HXN
 * @date 2026-09-28
 * @description 自定义测试结果列排序请求 DTO
 */
package com.platform.execution.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 自定义测试结果列上移/下移请求（与相邻列交换 sort_no）
 */
@Data
public class ResultColumnMoveRequest {

    /**
     * 移动方向：up-上移 / down-下移
     */
    @NotBlank(message = "移动方向不能为空")
    private String direction;
}
