/**
 * @author HXN
 * @date 2026-08-30
 * @description 需求版本实体类
 */
package com.platform.requirement.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 需求版本实体
 *
 * <p>项目下的需求版本，每个版本包含多个需求条目。
 * 状态：PLANNING（规划中）→ IN_PROGRESS（进行中）→ COMPLETED（已完成）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("requirement_version")
public class RequirementVersion extends BaseEntity {

    /**
     * 所属项目 ID
     */
    private Long projectId;

    /**
     * 所属分组 ID（指向系统分组「未分组」或用户分组）
     */
    private Long groupId;

    /**
     * 版本号
     */
    private String versionName;

    /**
     * 版本描述
     */
    private String description;

    /**
     * 状态：PLANNING-规划中，IN_PROGRESS-进行中，COMPLETED-已完成
     */
    private String status;

    /**
     * 计划开始日期
     */
    private LocalDate startDate;

    /**
     * 计划结束日期
     */
    private LocalDate endDate;
}
