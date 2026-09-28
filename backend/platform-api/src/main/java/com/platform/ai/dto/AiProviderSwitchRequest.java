/**
 * @author HXN
 * @date 2026-09-28
 * @description AI 服务提供商切换 DTO
 */
package com.platform.ai.dto;

import lombok.Data;

/**
 * AI 提供商切换请求
 */
@Data
public class AiProviderSwitchRequest {

    /**
     * 目标提供商：bailian / qoder
     */
    private String provider;
}
