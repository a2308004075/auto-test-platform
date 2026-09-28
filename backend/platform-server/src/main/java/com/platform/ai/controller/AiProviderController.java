/**
 * @author HXN
 * @date 2026-09-28
 * @description AI 服务提供商控制器
 */
package com.platform.ai.controller;

import com.platform.ai.dto.AiProviderStatusResponse;
import com.platform.ai.dto.AiProviderSwitchRequest;
import com.platform.ai.dto.AiProviderTestRequest;
import com.platform.ai.service.AiProviderService;
import com.platform.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * AI 服务提供商管理接口（仅 ADMIN）
 *
 * <p>当前启用项查看/切换与连接测试。凭据均在后端 yml 配置，
 * 接口不回显任何凭据内容。</p>
 */
@RestController
@RequestMapping("/api/v1/ai/provider")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
public class AiProviderController {

    private final AiProviderService aiProviderService;

    /**
     * 查询提供商状态（当前启用项 + 两提供商只读连接信息）
     */
    @GetMapping
    public ApiResponse<AiProviderStatusResponse> status() {
        return ApiResponse.ok(aiProviderService.status());
    }

    /**
     * 切换当前启用的提供商
     */
    @PostMapping
    public ApiResponse<AiProviderStatusResponse> switchProvider(@Valid @RequestBody AiProviderSwitchRequest request) {
        aiProviderService.switchProvider(request.getProvider());
        return ApiResponse.ok(aiProviderService.status());
    }

    /**
     * 连接测试（bailian：配置完整性校验；qoder：真实调用）
     */
    @PostMapping("/test")
    public ApiResponse<String> testProvider(@Valid @RequestBody AiProviderTestRequest request) {
        return ApiResponse.success(aiProviderService.testProvider(request.getProvider()), "连接测试完成");
    }
}
