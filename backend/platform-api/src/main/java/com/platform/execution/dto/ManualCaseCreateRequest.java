/**
 * @author HXN
 * @date 2026-08-30
 * @description 手动化用例创建请求 DTO
 */
package com.platform.execution.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 手动化用例创建请求
 *
 * <p>用例类型、优先级、执行环境等属性由【页面配置-编辑手动用例】动态字段驱动，
 * 经 customFields（fieldKey -&gt; 值）提交；新建时暂存的附件与关联随创建一并落库。</p>
 */
@Data
public class ManualCaseCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "用例标题不能为空")
    @Size(max = 200, message = "用例标题长度不能超过 200")
    private String title;

    private String content;

    /**
     * 所属分组 ID
     */
    private Long groupId;

    /**
     * 动态字段值（fieldKey -&gt; 值），由【页面配置】驱动
     */
    private Map<String, String> customFields;

    /**
     * 初始附件（新建时暂存，随创建提交）
     */
    @Valid
    private List<ManualCaseAttachmentCreateRequest> attachments;

    /**
     * 初始关联需求条目 ID 列表（新建时暂存，随创建提交）
     */
    private List<Long> requirementItemIds;

    /**
     * 初始关联缺陷（新建时暂存，随创建提交）
     */
    @Valid
    private List<ManualCaseDefectRelationCreateRequest> defectRelations;
}
