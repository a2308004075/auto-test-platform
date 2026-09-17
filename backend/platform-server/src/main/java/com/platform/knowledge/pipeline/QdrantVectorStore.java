/**
 * @author HXN
 * @date 2026-09-15
 * @description Qdrant 向量存储客户端
 */
package com.platform.knowledge.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.common.exception.BusinessException;
import com.platform.common.exception.ErrorCode;
import com.platform.knowledge.config.KnowledgeConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Qdrant 向量存储封装
 *
 * <p>通过 Qdrant REST API 提供 collection 管理（创建/删除）、向量插入、相似度检索等能力。
 * 每个知识库对应一个独立的 collection，命名规则为 {@code kb_{knowledgeBaseId}}。</p>
 */
@Slf4j
@Service
public class QdrantVectorStore {

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    private final String baseUrl;
    private final KnowledgeConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public QdrantVectorStore(KnowledgeConfig config, ObjectMapper objectMapper) {
        KnowledgeConfig.QdrantProps props = config.getQdrant();
        this.baseUrl = "http://" + props.getHost() + ":" + props.getPort();
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 获取知识库对应的 collection 名称
     */
    public String collectionName(Long knowledgeBaseId) {
        return config.getQdrant().getCollectionPrefix() + knowledgeBaseId;
    }

    /**
     * 创建 collection（如已存在则跳过）
     */
    public void ensureCollection(Long knowledgeBaseId, int dimension) {
        String name = collectionName(knowledgeBaseId);

        // 检查是否已存在
        try {
            Request checkReq = new Request.Builder()
                    .url(baseUrl + "/collections/" + name)
                    .get().build();
            try (Response checkResp = httpClient.newCall(checkReq).execute()) {
                if (checkResp.isSuccessful()) {
                    log.debug("Collection 已存在: {}", name);
                    return;
                }
            }
        } catch (IOException e) {
            // 连接失败，将在创建时暴露
        }

        // 创建 collection（named vector + Cosine）
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode vectors = body.putObject("vectors");
        ObjectNode embedding = vectors.putObject("embedding");
        embedding.put("size", dimension);
        embedding.put("distance", "Cosine");

        executeRequest("PUT", "/collections/" + name, body, "创建 Qdrant Collection 失败");

        log.info("Collection 创建成功: {} (dimension={})", name, dimension);
    }

    /**
     * 批量插入向量
     *
     * @param knowledgeBaseId 知识库 ID
     * @param entries         插入条目列表
     */
    public void insertVectors(Long knowledgeBaseId, List<VectorEntry> entries) {
        if (entries.isEmpty()) return;

        String name = collectionName(knowledgeBaseId);

        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode points = body.putArray("points");

        for (VectorEntry entry : entries) {
            ObjectNode point = points.addObject();
            point.put("id", entry.vectorId);

            ArrayNode vec = point.putArray("vector");
            for (Float f : entry.vector) {
                vec.add(f);
            }

            ObjectNode payload = point.putObject("payload");
            // Qdrant payload 文本截断到 16000 字符
            String text = entry.text;
            if (text.length() > 16000) {
                text = text.substring(0, 16000);
            }
            payload.put("text", text);
            payload.put("doc_id", entry.documentId);
            payload.put("chunk_index", entry.chunkIndex);
        }

        executeRequest("PUT", "/collections/" + name + "/points?wait=true", body, "向量插入失败");

        log.info("向量插入成功: collection={}, count={}", name, entries.size());
    }

    /**
     * 相似度检索
     *
     * @param knowledgeBaseId 知识库 ID
     * @param queryVector     查询向量
     * @param topK            返回数量
     * @return 检索结果列表（按相似度降序）
     */
    public List<SearchHit> search(Long knowledgeBaseId, List<Float> queryVector, int topK) {
        String name = collectionName(knowledgeBaseId);

        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode vec = body.putArray("vector");
        for (Float f : queryVector) {
            vec.add(f);
        }
        body.put("limit", topK);
        body.put("with_payload", true);

        try {
            String json = objectMapper.writeValueAsString(body);
            Request request = new Request.Builder()
                    .url(baseUrl + "/collections/" + name + "/points/search")
                    .post(RequestBody.create(json, JSON_MEDIA))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new BusinessException(ErrorCode.KB_QDRANT_UNAVAILABLE,
                            "向量检索失败: HTTP " + response.code());
                }

                JsonNode root = objectMapper.readTree(response.body().string());
                JsonNode resultArray = root.get("result");

                List<SearchHit> hits = new ArrayList<>();
                if (resultArray != null && resultArray.isArray()) {
                    for (JsonNode point : resultArray) {
                        SearchHit hit = new SearchHit();
                        hit.vectorId = point.get("id").asText();
                        hit.score = point.get("score").asDouble();

                        JsonNode payload = point.get("payload");
                        if (payload != null) {
                            hit.text = payload.has("text") ? payload.get("text").asText() : "";
                            hit.documentId = payload.has("doc_id") ? payload.get("doc_id").asLong() : 0L;
                            hit.chunkIndex = payload.has("chunk_index") ? payload.get("chunk_index").asInt() : 0;
                        }
                        hits.add(hit);
                    }
                }

                log.debug("向量检索完成: collection={}, topK={}, hits={}", name, topK, hits.size());
                return hits;
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.KB_QDRANT_UNAVAILABLE,
                    "向量检索失败: " + e.getMessage());
        }
    }

    /**
     * 删除 collection（知识库删除时调用）
     */
    public void dropCollection(Long knowledgeBaseId) {
        String name = collectionName(knowledgeBaseId);
        try {
            Request request = new Request.Builder()
                    .url(baseUrl + "/collections/" + name)
                    .delete()
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                // 忽略 404（collection 可能不存在）
            }
            log.info("Collection 删除: {}", name);
        } catch (Exception e) {
            log.error("删除 Collection 失败: {}", name, e);
        }
    }

    /**
     * 删除指定文档的所有向量（文档删除时调用）
     */
    public void deleteByDocumentId(Long knowledgeBaseId, Long documentId) {
        String name = collectionName(knowledgeBaseId);

        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode filter = body.putObject("filter");
        ObjectNode must = filter.putObject("must");
        ObjectNode keyMatch = must.putObject("key");
        keyMatch.put("key", "doc_id");
        ObjectNode match = keyMatch.putObject("match");
        match.put("value", documentId);

        try {
            executeRequest("POST", "/collections/" + name + "/points/delete?wait=true",
                    body, "删除文档向量失败");
            log.info("删除文档向量: collection={}, docId={}", name, documentId);
        } catch (Exception e) {
            log.error("删除文档向量失败: collection={}, docId={}", name, documentId, e);
        }
    }

    // ===== 内部方法 =====

    private void executeRequest(String method, String path, ObjectNode body, String errorPrefix) {
        try {
            String json = objectMapper.writeValueAsString(body);
            RequestBody requestBody = RequestBody.create(json, JSON_MEDIA);

            Request request;
            switch (method) {
                case "PUT":
                    request = new Request.Builder()
                            .url(baseUrl + path)
                            .put(requestBody)
                            .build();
                    break;
                case "POST":
                    request = new Request.Builder()
                            .url(baseUrl + path)
                            .post(requestBody)
                            .build();
                    break;
                case "DELETE":
                    request = new Request.Builder()
                            .url(baseUrl + path)
                            .delete()
                            .build();
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported HTTP method: " + method);
            }

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String respBody = response.body() != null ? response.body().string() : "";
                    log.error("{}: HTTP {}, body={}", errorPrefix, response.code(), respBody);
                    throw new BusinessException(ErrorCode.KB_QDRANT_UNAVAILABLE,
                            errorPrefix + ": HTTP " + response.code());
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.KB_QDRANT_UNAVAILABLE,
                    errorPrefix + ": " + e.getMessage());
        }
    }

    // ===== 数据结构 =====

    public static class VectorEntry {
        public String vectorId;
        public Long documentId;
        public int chunkIndex;
        public String text;
        public List<Float> vector;
    }

    public static class SearchHit {
        public String vectorId;
        public double score;
        public String text;
        public long documentId;
        public int chunkIndex;
    }
}

