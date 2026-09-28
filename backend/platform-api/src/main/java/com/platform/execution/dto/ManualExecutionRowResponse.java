/**
 * @author HXN
 * @date 2026-09-28
 * @description 手动计划执行页用例行响应 DTO
 */
package com.platform.execution.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 手动计划执行页的用例行响应
 *
 * <p>一行对应计划关联的一条手动化用例（及其预创建的 test_result 记录），
 * 含标题/内容/动态字段值（弹窗“用例信息”展示）与多轮结果集合。
 */
@Data
public class ManualExecutionRowResponse {

    /**
     * 测试结果记录 ID（test_result.id，单元格更新的定位键）
     */
    private Long resultId;

    /**
     * 手动化用例 ID
     */
    private Long manualCaseId;

    /**
     * 用例标题
     */
    private String title;

    /**
     * 用例类型：NORMAL-正常，EXCEPTION-异常
     */
    private String caseType;

    /**
     * 优先级：高/中/低
     */
    private String priority;

    /**
     * 用例状态（1-使用，0-废弃）
     */
    private Integer caseStatus;

    /**
     * 用例内容（富文本：前置条件/操作步骤/预期结果）
     */
    private String content;

    /**
     * 所属分组 ID（null 表示未分组）
     */
    private Long groupId;

    /**
     * 动态字段值：fieldKey → value（与手动用例列表/详情同源，弹窗“用例信息”展示用）
     */
    private Map<String, String> customFields;

    /**
     * 用例附件列表（按上传时间倒序，弹窗“用例附件”展示用；createdByName 不填充）
     */
    private List<ManualCaseAttachmentResponse> attachments;

    /**
     * 多轮结果：结果列 ID → {status, remark}
     */
    private Map<String, RoundCell> roundResults;
}
