/**
 * @author HXN
 * @date 2026-09-28
 * @description AI 服务提供商切换 DTO
 */
package com.platform.ai.dto;

import lombok.Data;

import java.util.List;

/**
 * AI 提供商状态响应（连接凭据不回显，仅呈现配置完整性与非敏感项）
 */
@Data
public class AiProviderStatusResponse {

    /**
     * 当前启用的提供商：bailian / qoder
     */
    private String currentProvider;

    /**
     * 可选提供商列表与各自状态
     */
    private List<ProviderInfo> providers;

    /**
     * 单个提供商的只读状态信息
     */
    @Data
    public static class ProviderInfo {

        /**
         * 提供商标识：bailian / qoder
         */
        private String provider;

        /**
         * 提供商显示名
         */
        private String displayName;

        /**
         * 是否当前启用
         */
        private Boolean active;

        /**
         * 连接配置是否完整（可用于切换/调用）
         */
        private Boolean configured;

        /**
         * 连接地址（非敏感）
         */
        private String baseUrl;

        /**
         * 对话模型名（bailian）
         */
        private String chatModel;

        /**
         * Agent ID（qoder）
         */
        private String agentId;

        /**
         * Environment ID（qoder）
         */
        private String environmentId;

        /**
         * 凭据是否已配置（不回显凭据本身）
         */
        private Boolean credentialConfigured;
    }
}
