/**
 * @author HXN
 * @date 2026-09-28
 * @description AI 服务提供商切换 DTO
 */
package com.platform.ai.dto;

import lombok.Data;

/**
 * AI 提供商连接测试请求
 */
@Data
public class AiProviderTestRequest {

    /**
     * 待测试提供商：bailian / qoder
     */
    private String provider;
}
