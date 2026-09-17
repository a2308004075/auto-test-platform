/**
 * @author HXN
 * @date 2026-08-30
 * @description 需求版本创建/更新请求 DTO
 */
package com.platform.requirement.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 创建/更新需求版本请求
 */
@Data
public class RequirementVersionCreateRequest {

    private Long projectId;

    /**
     * 所属分组 ID（为空时默认归属项目「未分组」系统分组）
     */
    private Long groupId;

    @NotBlank(message = "版本号不能为空")
    @Size(max = 100, message = "版本号长度不能超过 100")
    private String versionName;

    @Size(max = 500, message = "版本描述长度不能超过 500")
    private String description;

    private String status;

    private LocalDate startDate;

    private LocalDate endDate;
}
