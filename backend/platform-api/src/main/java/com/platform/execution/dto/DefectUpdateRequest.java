/**
 * @author HXN
 * @date 2026-08-30
 * @description 缺陷更新请求 DTO
 */
package com.platform.execution.dto;

import lombok.Data;

import javax.validation.constraints.Size;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * 缺陷更新请求（支持部分更新）
 */
@Data
public class DefectUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long groupId;

    @Size(max = 500, message = "缺陷标题长度不能超过 500")
    private String title;

    private String content;

    private Long assigneeId;

    private LocalDate dueDate;

    @Size(max = 50, message = "发现版本长度不能超过 50")
    private String foundVersion;

    @Size(max = 100, message = "所属模块长度不能超过 100")
    private String moduleName;

    private String severity;

    private String source;

    private Long environmentId;

    private String reasonDescription;

    private Long responsibleId;

    @Size(max = 50, message = "修改版本长度不能超过 50")
    private String fixedVersion;

    private LocalDate planTestDate;

    private Long parentId;

    private BigDecimal estimatedHours;

    private BigDecimal actualHours;

    private BigDecimal remainingHours;

    /**
     * 自定义字段值（fieldKey -> 值，由【字段管理】动态配置驱动）
     */
    private Map<String, String> customFields;
}
