/**
 * @author HXN
 * @date 2026-09-28
 * @description AI 服务提供商配置属性
 */
package com.platform.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AI 服务提供商全局配置
 *
 * <p>读取 {@code ai.*} 配置项。当前启用的对话提供商以数据库
 * {@code global_settings} 的 {@code ai.provider} 为准（前端【AI 服务】页切换，
 * 运行时生效）；本配置中 {@code ai.provider} 仅为数据库未设置时的兜底默认值。
 * 百炼参数沿用 {@code knowledge.llm} 段；Qoder Cloud Agents 凭据仅后端持有。</p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai")
public class AiProviderConfig {

    /**
     * 兜底默认提供商：bailian / qoder
     */
    private String provider = "bailian";

    private QoderProps qoder = new QoderProps();

    @Data
    public static class QoderProps {
        /** Cloud Agents API 基础地址 */
        private String apiBaseUrl = "https://api.qoder.com";
        /** 访问令牌（PAT/SAT，通过环境变量 QODER_ACCESS_TOKEN 注入） */
        private String accessToken = "";
        /** Agent ID（Qoder 控制台预建） */
        private String agentId = "";
        /** Environment ID（Qoder 控制台预建） */
        private String environmentId = "";
        /** 单次对话超时（秒），覆盖建会话/发消息/事件流全程 */
        private int timeoutSeconds = 300;
    }
}
