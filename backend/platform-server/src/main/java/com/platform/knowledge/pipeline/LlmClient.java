/**
 * @author HXN
 * @date 2026-09-15
 * @description LLM 客户端
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI 兼容 LLM 客户端
 *
 * <p>支持普通调用和 SSE 流式输出。通过 OkHttp 调用 /v1/chat/completions 接口。</p>
 */
@Slf4j
@Service
public class LlmClient {

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    private final KnowledgeConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public LlmClient(KnowledgeConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(config.getLlm().getTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 普通（非流式）对话调用
     *
     * @param messages    消息列表（role/content 对）
     * @param temperature 温度参数（null 使用默认）
     * @return 助手回复文本
     */
    public ChatResult chat(List<ChatMessage> messages, Double temperature) {
        try {
            String json = buildRequestBody(messages, temperature, false);
            String url = config.getLlm().getBaseUrl() + "/chat/completions";

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + config.getLlm().getApiKey())
                    .post(RequestBody.create(json, JSON_MEDIA))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    String errorBody = response.body() != null ? response.body().string() : "无响应体";
                    throw new BusinessException(ErrorCode.KB_LLM_CALL_FAILED,
                            "LLM 调用失败: HTTP " + response.code() + " - " + errorBody);
                }

                JsonNode root = objectMapper.readTree(response.body().string());
                JsonNode choices = root.get("choices");
                if (choices == null || choices.size() == 0) {
                    throw new BusinessException(ErrorCode.KB_LLM_CALL_FAILED, "LLM 响应无 choices");
                }

                String content = choices.get(0).path("message").path("content").asText("");
                int tokensUsed = root.path("usage").path("total_tokens").asInt(0);

                return new ChatResult(content, tokensUsed);
            }
        } catch (IOException e) {
            log.error("LLM 调用异常", e);
            throw new BusinessException(ErrorCode.KB_LLM_CALL_FAILED, "LLM 调用异常: " + e.getMessage());
        }
    }

    /**
     * 流式对话调用（通过 SseEmitter 逐 token 推送）
     *
     * @param messages    消息列表
     * @param temperature 温度参数
     * @param emitter     SSE 发送器
     * @return 完整回复文本
     */
    public String chatStream(List<ChatMessage> messages, Double temperature, SseEmitter emitter) {
        StringBuilder fullContent = new StringBuilder();
        try {
            String json = buildRequestBody(messages, temperature, true);
            String url = config.getLlm().getBaseUrl() + "/chat/completions";

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + config.getLlm().getApiKey())
                    .post(RequestBody.create(json, JSON_MEDIA))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    String errorBody = response.body() != null ? response.body().string() : "无响应体";
                    throw new BusinessException(ErrorCode.KB_LLM_CALL_FAILED,
                            "LLM 流式调用失败: HTTP " + response.code());
                }

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6).trim();
                        if ("[DONE]".equals(data)) break;

                        try {
                            JsonNode chunk = objectMapper.readTree(data);
                            JsonNode delta = chunk.path("choices").path(0).path("delta");
                            String content = delta.path("content").asText(null);
                            if (content != null && !content.isEmpty()) {
                                fullContent.append(content);
                                // 推送给前端
                                emitter.send(SseEmitter.event()
                                        .name("token")
                                        .data(content));
                            }
                        } catch (Exception ignored) {
                            // 跳过非 JSON 行
                        }
                    }
                }
            }
            // 注意：不在此处发送 done/complete，由调用方在消息落库后收尾，
            // 避免前端提前收到完成信号时消息尚未保存的竞态

        } catch (Exception e) {
            log.error("LLM 流式调用异常", e);
            // 不直接操作 emitter，统一交由调用方发送 error 事件并收尾，
            // 避免 completeWithError 触发 ERROR dispatch 与全局异常处理器冲突
            throw new BusinessException(ErrorCode.KB_LLM_CALL_FAILED,
                    "LLM 流式调用异常: " + e.getMessage());
        }

        return fullContent.toString();
    }

    private String buildRequestBody(List<ChatMessage> messages, Double temperature, boolean stream) throws IOException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", config.getLlm().getChatModel());
        body.put("max_tokens", config.getLlm().getMaxTokens());
        body.put("temperature", temperature != null ? temperature : config.getLlm().getTemperature());
        body.put("stream", stream);

        ArrayNode messagesNode = objectMapper.createArrayNode();
        for (ChatMessage msg : messages) {
            ObjectNode msgNode = objectMapper.createObjectNode();
            msgNode.put("role", msg.role);
            msgNode.put("content", msg.content);
            messagesNode.add(msgNode);
        }
        body.set("messages", messagesNode);

        return objectMapper.writeValueAsString(body);
    }

    // ===== 数据结构 =====

    public static class ChatMessage {
        public String role;
        public String content;

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public static ChatMessage system(String content) { return new ChatMessage("system", content); }
        public static ChatMessage user(String content) { return new ChatMessage("user", content); }
        public static ChatMessage assistant(String content) { return new ChatMessage("assistant", content); }
    }

    public static class ChatResult {
        public final String content;
        public final int tokensUsed;

        public ChatResult(String content, int tokensUsed) {
            this.content = content;
            this.tokensUsed = tokensUsed;
        }
    }
}
