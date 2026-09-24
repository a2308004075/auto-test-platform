/**
 * @author HXN
 * @date 2026-09-24
 * @description 手动化用例简要信息 DTO（测试计划详情关联用例表格展示用）
 */
package com.platform.execution.dto;

import lombok.Data;

/**
 * 手动化用例简要信息
 *
 * <p>用于测试计划关联内容页"关联手动化用例"表格展示，避免前端二次反查。
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
}
