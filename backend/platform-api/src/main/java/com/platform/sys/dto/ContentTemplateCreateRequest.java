/**
 * @author HXN
 * @date 2026-09-22
 * @description 内容模板创建/更新请求 DTO
 */
package com.platform.sys.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

/**
 * 内容模板创建/更新请求
 */
@Data
public class ContentTemplateCreateRequest {

    /**
     * 所属项目 ID
     */
    @NotNull(message = "项目 ID 不能为空")
    private Long projectId;

    /**
     * 业务类型：defect-缺陷，manual_case-手动用例，requirement-需求
     */
    @NotBlank(message = "业务类型不能为空")
    private String bizType;

    /**
     * 模板名称（同一项目 + 同一业务类型内不可重复）
     */
    @NotBlank(message = "模板名称不能为空")
    @Size(max = 50, message = "模板名称最多 50 字")
    private String name;

    /**
     * 模板内容（富文本 HTML）
     */
    @NotBlank(message = "模板内容不能为空")
    private String content;
}
