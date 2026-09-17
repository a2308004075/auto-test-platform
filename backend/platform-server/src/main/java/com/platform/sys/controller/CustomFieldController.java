/**
 * @author HXN
 * @date 2026-08-30
 * @description 自定义字段管理控制器
 */
package com.platform.sys.controller;

import com.platform.common.response.ApiResponse;
import com.platform.sys.dto.CustomFieldCreateRequest;
import com.platform.sys.dto.CustomFieldListItem;
import com.platform.sys.dto.CustomFieldRenderDTO;
import com.platform.sys.dto.CustomFieldSortRequest;
import com.platform.sys.service.CustomFieldService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

/**
 * 自定义字段管理接口
 */
@RestController
@RequestMapping("/api/v1/custom-fields")
@RequiredArgsConstructor
public class CustomFieldController {

    private final CustomFieldService customFieldService;

    /**
     * 管理列表（按项目/模块/视图筛选）
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public ApiResponse<List<CustomFieldListItem>> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String viewType) {
        return ApiResponse.ok(customFieldService.listByConfig(projectId, module, viewType));
    }

    /**
     * 渲染列表（业务页面用，仅返回启用的字段）
     */
    @GetMapping("/render")
    public ApiResponse<List<CustomFieldRenderDTO>> render(
            @RequestParam Long projectId,
            @RequestParam String module,
            @RequestParam String viewType) {
        return ApiResponse.ok(customFieldService.listForRender(projectId, module, viewType));
    }

    /**
     * 新建字段
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public ApiResponse<CustomFieldListItem> create(@Valid @RequestBody CustomFieldCreateRequest request) {
        return ApiResponse.ok(customFieldService.create(request));
    }

    /**
     * 更新字段
     */
    @PostMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public ApiResponse<CustomFieldListItem> update(@PathVariable Long id,
                                                   @Valid @RequestBody CustomFieldCreateRequest request) {
        return ApiResponse.ok(customFieldService.update(id, request));
    }

    /**
     * 批量排序（拖拽调整字段顺序）
     */
    @PostMapping("/sort")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public ApiResponse<Void> sort(@Valid @RequestBody CustomFieldSortRequest request) {
        customFieldService.sort(request.getOrderedIds());
        return ApiResponse.ok(null);
    }

    /**
     * 删除字段
     */
    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        customFieldService.delete(id);
        return ApiResponse.ok(null);
    }
}
