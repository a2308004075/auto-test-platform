/**
 * @author HXN
 * @date 2026-09-28
 * @description 多轮测试结果单元格值 DTO
 */
package com.platform.execution.dto;

import lombok.Data;

/**
 * 多轮测试结果单元格值（“用例行 × 自定义结果列”的一个格子）
 */
@Data
public class RoundCell {

    /**
     * 结果状态：PASSED / FAILED / SKIPPED
     */
    private String status;

    /**
     * 文本备注
     */
    private String remark;
}
