/**
 * @author HXN
 * @date 2026-08-22 13:28
 * @description 字典查询控制器
 */
package com.platform.sys.controller;

import com.platform.common.response.ApiResponse;
import com.platform.sys.dto.DictListItem;
import com.platform.sys.service.DictService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 字典查询接口（字典管理功能已下线，仅保留字典数据读取）
 */
@RestController
@RequestMapping("/api/v1/sys/dicts")
@RequiredArgsConstructor
public class DictController {

    private final DictService dictService;

    /**
     * 根据字典类型查询字典值列表（公开接口，无需登录）
     */
    @GetMapping("/type/{dictType}")
    public ApiResponse<List<DictListItem>> getByType(@PathVariable String dictType) {
        return ApiResponse.ok(dictService.getByType(dictType));
    }
}
