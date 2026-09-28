/**
 * @author HXN
 * @date 2026-09-28
 * @description 自定义测试结果列响应 DTO
 */
package com.platform.execution.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 自定义测试结果列响应
 */
@Data
public class ResultColumnResponse {

    private Long id;

    private Long planId;

    /**
     * 列名称（用户自定义）
     */
    private String columnName;

    /**
     * 列顺序
     */
    private Integer sortNo;

    private Long createdBy;

    private LocalDateTime createdAt;
}
