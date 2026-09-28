/**
 * @author HXN
 * @date 2026-09-28
 * @description 多轮测试结果单元格更新请求 DTO
 */
package com.platform.execution.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

/**
 * 多轮测试结果单元格更新请求
 *
 * <p>覆盖式更新该单元格的 status 与 remark；
 * 两者均为空视为清除该格记录（移除对应列条目）。
 */
@Data
public class RoundResultUpdateRequest {

    /**
     * 测试结果记录 ID（test_result.id）
     */
    @NotNull(message = "结果 ID 不能为空")
    private Long resultId;

    /**
     * 自定义结果列 ID（plan_result_column.id）
     */
    @NotNull(message = "结果列 ID 不能为空")
    private Long columnId;

    /**
     * 结果状态：PASSED / FAILED / SKIPPED（可空，表示仅更新备注或清除）
     */
    private String status;

    /**
     * 文本备注（可空）
     */
    private String remark;
}
