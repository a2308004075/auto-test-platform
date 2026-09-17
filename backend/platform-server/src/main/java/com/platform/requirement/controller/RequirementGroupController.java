/**
 * @author HXN
 * @date 2026-09-15
 * @description 需求分组管理控制器
 */
package com.platform.requirement.controller;

import com.platform.common.response.ApiResponse;
import com.platform.requirement.dto.RequirementGroupCreateRequest;
import com.platform.requirement.dto.RequirementGroupResponse;
import com.platform.requirement.dto.RequirementGroupUpdateRequest;
import com.platform.requirement.service.RequirementGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

/**
 * 需求分组管理接口
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/requirement-groups")
@RequiredArgsConstructor
public class RequirementGroupController {

    private final RequirementGroupService requirementGroupService;

    /**
     * 查询分组列表
     */
    @GetMapping
    public ApiResponse<List<RequirementGroupResponse>> list(@PathVariable Long projectId) {
        return ApiResponse.ok(requirementGroupService.listByProject(projectId));
    }

    /**
     * 创建分组
     */
    @PostMapping
    public ApiResponse<RequirementGroupResponse> create(@PathVariable Long projectId,
                                                        @Valid @RequestBody RequirementGroupCreateRequest request) {
        request.setProjectId(projectId);
        return ApiResponse.ok(requirementGroupService.create(request));
    }

    /**
     * 更新分组
     */
    @PostMapping("/{groupId}")
    public ApiResponse<RequirementGroupResponse> update(@PathVariable Long projectId,
                                                        @PathVariable Long groupId,
                                                        @Valid @RequestBody RequirementGroupUpdateRequest request) {
        return ApiResponse.ok(requirementGroupService.update(groupId, request));
    }

    /**
     * 删除分组
     */
    @PostMapping("/{groupId}/delete")
    public ApiResponse<Void> delete(@PathVariable Long projectId,
                                    @PathVariable Long groupId) {
        requirementGroupService.delete(groupId);
        return ApiResponse.ok();
    }
}
