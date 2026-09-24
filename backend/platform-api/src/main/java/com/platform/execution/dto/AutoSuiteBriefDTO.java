/**
 * @author HXN
 * @date 2026-09-24
 * @description 自动化套件简要信息 DTO（测试计划详情关联套件表格展示用）
 */
package com.platform.execution.dto;

import lombok.Data;

/**
 * 自动化套件简要信息
 *
 * <p>用于测试计划详情页"关联自动化套件"表格展示，避免前端二次反查。
 */
@Data
public class AutoSuiteBriefDTO {

    /**
     * 套件 ID
     */
    private Long id;

    /**
     * 套件名称
     */
    private String name;

    /**
     * 套件下启用的自动化用例数
     */
    private Integer caseCount;
}
