/**
 * @author HXN
 * @date 2026-09-15
 * @description 需求分组响应 DTO
 */
package com.platform.requirement.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 需求分组响应
 */
@Data
public class RequirementGroupResponse {

    private Long id;

    private Long projectId;

    private Long parentId;

    private String name;

    private String description;

    private Integer isSystem;

    /**
     * 分组下需求条目总数（含子孙分组，自底向上聚合）
     */
    private Integer itemCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
