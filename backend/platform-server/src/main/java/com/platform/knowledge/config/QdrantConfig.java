/**
 * @author HXN
 * @date 2026-09-15
 * @description Qdrant 连接配置
 */
package com.platform.knowledge.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * Qdrant 连接配置
 *
 * <p>QdrantVectorStore 内部创建 OkHttpClient，无需额外 Bean 定义。</p>
 */
@Slf4j
@Configuration
public class QdrantConfig {

    private final KnowledgeConfig knowledgeConfig;

    public QdrantConfig(KnowledgeConfig knowledgeConfig) {
        this.knowledgeConfig = knowledgeConfig;
        KnowledgeConfig.QdrantProps props = knowledgeConfig.getQdrant();
        log.info("Qdrant 配置: {}:{}", props.getHost(), props.getPort());
    }
}
