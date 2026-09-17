/**
 * @author HXN
 * @date 2026-09-15
 * @description 向量嵌入服务
 */
package com.platform.knowledge.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.common.exception.BusinessException;
import com.platform.common.exception.ErrorCode;
import com.platform.knowledge.config.KnowledgeConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 向量嵌入服务
 *
 * <p>调用 OpenAI 兼容 Embedding API，将文本转为浮点向量。
 * 支持单条和批量嵌入。</p>
 */
@Slf4j
@Service
public class EmbeddingService {

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    private final KnowledgeConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public EmbeddingService(KnowledgeConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(config.getLlm().getTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 单条文本嵌入
     */
    public List<Float> embed(String text) {
        List<List<Float>> results = embedBatch(java.util.Collections.singletonList(text));
        return results.get(0);
    }

    /**
     * 批量文本嵌入
     *
     * @param texts 文本列表
     * @return 向量列表（与 texts 顺序一致）
     */
    public List<List<Float>> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }

        try {
            // 构建请求体；dimensions 为 OpenAI 兼容可选参数，
            // 仅部分模型支持（如 text-embedding-v3/v4），取值需与配置的嵌入维度一致
            ObjectNode requestNode = objectMapper.createObjectNode();
            requestNode.put("model", config.getLlm().getEmbeddingModel());
            requestNode.put("dimensions", config.getLlm().getEmbeddingDimension());
            requestNode.set("input", objectMapper.valueToTree(texts));

            String json = objectMapper.writeValueAsString(requestNode);
            String url = config.getLlm().getBaseUrl() + "/embeddings";

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + config.getLlm().getApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(json, JSON_MEDIA))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    String errorBody = response.body() != null ? response.body().string() : "无响应体";
                    log.error("Embedding API 调用失败: status={}, body={}", response.code(), errorBody);
                    throw new BusinessException(ErrorCode.KB_EMBEDDING_FAILED,
                            "嵌入 API 调用失败: HTTP " + response.code());
                }

                String responseBody = response.body().string();
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode data = root.get("data");
                if (data == null || !data.isArray()) {
                    throw new BusinessException(ErrorCode.KB_EMBEDDING_FAILED, "嵌入 API 响应格式异常");
                }

                List<List<Float>> vectors = new ArrayList<>();
                for (JsonNode item : data) {
                    JsonNode embedding = item.get("embedding");
                    List<Float> vector = new ArrayList<>();
                    if (embedding != null && embedding.isArray()) {
                        for (JsonNode val : embedding) {
                            vector.add((float) val.asDouble());
                        }
                    }
                    vectors.add(vector);
                }

                log.debug("嵌入完成: {} 条文本 -> {} 个向量 (维度={})",
                        texts.size(), vectors.size(),
                        vectors.isEmpty() ? 0 : vectors.get(0).size());
                return vectors;
            }
        } catch (IOException e) {
            log.error("Embedding API 调用异常", e);
            throw new BusinessException(ErrorCode.KB_EMBEDDING_FAILED,
                    "嵌入 API 调用异常: " + e.getMessage());
        }
    }
}
