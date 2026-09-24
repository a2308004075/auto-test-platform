/**
 * @author HXN
 * @date 2026-08-30
 * @description 手动化用例更新请求 DTO
 */
package com.platform.execution.dto;

import lombok.Data;

import javax.validation.constraints.Size;
import java.io.Serializable;
import java.util.Map;

/**
 * 手动化用例更新请求（支持部分更新）
 *
 * <p>属性字段（用例类型/优先级/执行环境等）由【页面配置】动态字段驱动，经 customFields 提交；
 * 状态变更走专用状态接口，不在此处提交。</p>
 */
@Data
public class ManualCaseUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @Size(max = 200, message = "用例标题长度不能超过 200")
    private String title;

    private String content;

    /**
     * 所属分组 ID
     */
    private Long groupId;

    /**
     * 动态字段值（fieldKey -&gt; 值），由【页面配置】驱动；null 表示本次不改动动态字段
     */
    private Map<String, String> customFields;
}
