/**
 * @author HXN
 * @date 2026-08-30
 * @description 需求版本响应 DTO
 */
package com.platform.requirement.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 需求版本响应
 */
@Data
public class RequirementVersionResponse {

    private Long id;

    private Long projectId;

    private Long groupId;

    private String versionName;

    private String description;

    private String status;

    private LocalDate startDate;

    private LocalDate endDate;

    /**
     * 该版本下的需求条目数量
     */
    private Integer itemCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
