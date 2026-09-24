/**
 * @author HXN
 * @date 2026-09-22
 * @description 内容模板管理控制器
 */
package com.platform.sys.controller;

import com.platform.common.response.ApiResponse;
import com.platform.sys.dto.ContentTemplateCreateRequest;
import com.platform.sys.dto.ContentTemplateListItem;
import com.platform.sys.service.ContentTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

/**
 * 内容模板管理接口（【页面配置-内容模板】）
 */
@RestController
@RequestMapping("/api/v1/content-templates")
@RequiredArgsConstructor
public class ContentTemplateController {

    private final ContentTemplateService contentTemplateService;

    /**
     * 列表（按项目；bizType 非空时按业务类型过滤。业务页新建页自动填模板与页面配置共用）
     */
    @GetMapping
    public ApiResponse<List<ContentTemplateListItem>> list(@RequestParam Long projectId,
                                                           @RequestParam(required = false) String bizType) {
        return ApiResponse.ok(contentTemplateService.listByProject(projectId, bizType));
    }

    /**
     * 新建模板
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public ApiResponse<ContentTemplateListItem> create(@Valid @RequestBody ContentTemplateCreateRequest request) {
        return ApiResponse.ok(contentTemplateService.create(request));
    }

    /**
     * 更新模板
     */
    @PostMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public ApiResponse<ContentTemplateListItem> update(@PathVariable Long id,
                                                       @Valid @RequestBody ContentTemplateCreateRequest request) {
        return ApiResponse.ok(contentTemplateService.update(id, request));
    }

    /**
     * 删除模板
     */
    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        contentTemplateService.delete(id);
        return ApiResponse.ok(null);
    }
}
