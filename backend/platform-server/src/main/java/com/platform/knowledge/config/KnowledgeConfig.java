/**
 * @author HXN
 * @date 2026-09-15
 * @description 知识库配置属性
 */
package com.platform.knowledge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 知识库全局配置
 *
 * <p>读取 {@code knowledge.*} 配置项，包含 LLM、Qdrant、分块策略三部分。</p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "knowledge")
public class KnowledgeConfig {

    private LlmConfig llm = new LlmConfig();
    private QdrantProps qdrant = new QdrantProps();
    private ChunkingProps chunking = new ChunkingProps();

    @Data
    public static class LlmConfig {
        /** OpenAI 兼容 API 基础地址 */
        private String baseUrl = "https://api.openai.com/v1";
        /** API Key */
        private String apiKey = "sk-xxx";
        /** 对话模型 */
        private String chatModel = "gpt-4o-mini";
        /** 嵌入模型 */
        private String embeddingModel = "text-embedding-3-small";
        /** 嵌入维度 */
        private int embeddingDimension = 1536;
        /** 最大输出 token 数 */
        private int maxTokens = 4096;
        /** 温度 */
        private double temperature = 0.7;
        /** 超时（秒） */
        private int timeoutSeconds = 120;
    }

    @Data
    public static class QdrantProps {
        private String host = "localhost";
        private int port = 6333;
        private String collectionPrefix = "kb_";
    }

    @Data
    public static class ChunkingProps {
        /** 每块最大 token 数 */
        private int chunkSize = 500;
        /** 重叠 token 数 */
        private int chunkOverlap = 50;
    }
}
