/**
 * @author HXN
 * @date 2026-08-30
 * @description 需求条目实体类
 */
package com.platform.requirement.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 需求条目实体
 *
 * <p>版本下的需求条目，包含标题、描述、优先级、状态、负责人等信息。
 * 需求类型：FEATURE（功能）、IMPROVEMENT（优化）、BUG（Bug）
 * 优先级：HIGH（高）、MEDIUM（中）、LOW（低）
 * 状态：PENDING（待处理）→ IN_PROGRESS（进行中）→ COMPLETED（已完成）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("requirement_item")
public class RequirementItem extends BaseEntity {

    /**
     * 所属版本 ID
     */
    private Long versionId;

    /**
     * 需求标题
     */
    private String title;

    /**
     * 需求描述
     */
    private String description;

    /**
     * 需求内容（富文本 HTML，与缺陷/手动用例"内容"一致；新建页可由【页面配置-内容模板】自动填入）
     */
    private String content;

    /**
     * 需求类型：FEATURE-功能，IMPROVEMENT-优化，BUG-Bug
     */
    private String reqType;

    /**
     * 优先级：HIGH-高，MEDIUM-中，LOW-低
     */
    private String priority;

    /**
     * 状态：PENDING-待处理，IN_PROGRESS-进行中，COMPLETED-已完成
     */
    private String status;

    /**
     * 负责人
     */
    private String assignee;

    /**
     * 截止日期
     */
    private LocalDate deadline;

    /**
     * 排序号
     */
    private Integer sortOrder;
}
