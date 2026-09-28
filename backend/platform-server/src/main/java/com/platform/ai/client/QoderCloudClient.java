/**
 * @author HXN
 * @date 2026-09-28
 * @description Qoder Cloud Agents API 客户端
 */
package com.platform.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.ai.config.AiProviderConfig;
import com.platform.common.exception.BusinessException;
import com.platform.common.exception.ErrorCode;
import com.platform.knowledge.pipeline.LlmClient;
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
 * Qoder Cloud Agents API 客户端
 *
 * <p>调用流程（对齐官方 Quickstart）：Bearer Token 鉴权 →
 * POST /api/v1/cloud/sessions 创建会话 → POST /sessions/{id}/events 发送
 * user.message → GET /sessions/{id}/events/stream 消费 SSE 事件流，
 * 至 {@code session.status_idle} 结束。每次对话新建 Session（无状态语义，
 * 与 {@link LlmClient} 的调用契约一致）。</p>
 *
 * <p>消息列表（system + 历史多轮 + 当前问题）拼接为单条 user.message 文本：
 * Qoder Agent 侧由预建 Agent 的 system prompt 承担系统指令角色，
 * 历史消息以标注角色的文本形式携带。</p>
 *
 * <p>事件流中 {@code agent.message} 为整段文本（非 token 增量），
 * 流式输出表现为分段推送；heartbeat/agent.thinking/tool_use/tool_result
 * 事件跳过（tool 相关仅 debug 日志）。</p>
 */
@Slf4j
@Service
public class QoderCloudClient {

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    private final AiProviderConfig aiConfig;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public QoderCloudClient(AiProviderConfig aiConfig, ObjectMapper objectMapper) {
        this.aiConfig = aiConfig;
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(aiConfig.getQoder().getTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 连接测试：GET /api/v1/cloud/environments
     *
     * @return 成功提示（含环境数量）
     */
    public String testConnection() {
        validateConfig();
        Request request = new Request.Builder()
                .url(baseUrl() + "/environments")
                .addHeader("Authorization", "Bearer " + aiConfig.getQoder().getAccessToken())
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.code() == 401 || response.code() == 403) {
                throw new BusinessException(ErrorCode.AI_PROVIDER_CALL_FAILED,
                        "Qoder 连接测试失败: Token 无效或已过期 (HTTP " + response.code() + ")");
            }
            if (!response.isSuccessful() || response.body() == null) {
                String errorBody = response.body() != null ? response.body().string() : "无响应体";
                throw new BusinessException(ErrorCode.AI_PROVIDER_CALL_FAILED,
                        "Qoder 连接测试失败: HTTP " + response.code() + " - " + errorBody);
            }
            JsonNode root = objectMapper.readTree(response.body().string());
            int envCount = root.path("data").size();
            return "连接成功，账号下共有 " + envCount + " 个云环境";
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            log.error("Qoder 连接测试异常", e);
            throw new BusinessException(ErrorCode.AI_PROVIDER_CALL_FAILED,
                    "Qoder 连接测试异常: " + e.getMessage());
        }
    }

    /**
     * 普通（非流式）对话调用
     *
     * @param messages    消息列表（role/content 对）
     * @param temperature 温度参数（Qoder Cloud Agents 不支持，忽略）
     * @return 助手回复文本（tokensUsed 记 0，Cloud Agents 不返回 token 用量）
     */
    public LlmClient.ChatResult chat(List<LlmClient.ChatMessage> messages, Double temperature) {
        validateConfig();
        try {
            String sessionId = createSession();
            String prompt = buildPrompt(messages);
            sendMessage(sessionId, prompt);
            String content = consumeStream(sessionId, null);
            return new LlmClient.ChatResult(content, 0);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Qoder 对话调用异常", e);
            throw new BusinessException(ErrorCode.AI_PROVIDER_CALL_FAILED,
                    "Qoder 对话调用异常: " + e.getMessage());
        }
    }

    /**
     * 流式对话调用（每个 agent.message 文本作为 token 事件推送）
     *
     * <p>沿用 {@link LlmClient#chatStream} 的 emitter 约定：异常时抛
     * BusinessException，由调用方发送 error 事件并收尾，不操作 emitter。</p>
     *
     * @param messages    消息列表
     * @param temperature 温度参数（忽略）
     * @param emitter     SSE 发送器（null 时不推送，仅返回文本）
     * @return 完整回复文本
     */
    public String chatStream(List<LlmClient.ChatMessage> messages, Double temperature, SseEmitter emitter) {
        validateConfig();
        try {
            String sessionId = createSession();
            String prompt = buildPrompt(messages);
            sendMessage(sessionId, prompt);
            return consumeStream(sessionId, emitter);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Qoder 流式对话调用异常", e);
            throw new BusinessException(ErrorCode.AI_PROVIDER_CALL_FAILED,
                    "Qoder 流式对话调用异常: " + e.getMessage());
        }
    }

    // ===== 内部步骤 =====

    /** Cloud Agents API 基础路径 */
    private String baseUrl() {
        return aiConfig.getQoder().getApiBaseUrl() + "/api/v1/cloud";
    }

    /** 配置完整性校验（token/agentId/environmentId 非空） */
    private void validateConfig() {
        AiProviderConfig.QoderProps qoder = aiConfig.getQoder();
        if (isBlank(qoder.getAccessToken())) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_CONFIG_INVALID,
                    "Qoder 访问令牌未配置，请设置环境变量 QODER_ACCESS_TOKEN");
        }
        if (isBlank(qoder.getAgentId()) || isBlank(qoder.getEnvironmentId())) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_CONFIG_INVALID,
                    "Qoder Agent ID / Environment ID 未配置，请设置 QODER_AGENT_ID 与 QODER_ENVIRONMENT_ID");
        }
    }

    /** 创建 Cloud Session，返回 session id */
    private String createSession() throws IOException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("agent", aiConfig.getQoder().getAgentId());
        body.put("environment_id", aiConfig.getQoder().getEnvironmentId());

        Request request = new Request.Builder()
                .url(baseUrl() + "/sessions")
                .addHeader("Authorization", "Bearer " + aiConfig.getQoder().getAccessToken())
                .post(RequestBody.create(objectMapper.writeValueAsString(body), JSON_MEDIA))
                .build();
        return executeForJson(request, "创建 Session 失败").path("id").asText(null);
    }

    /** 发送 user.message（消息列表拼接为单条文本） */
    private void sendMessage(String sessionId, String prompt) throws IOException {
        ObjectNode contentItem = objectMapper.createObjectNode();
        contentItem.put("type", "text");
        contentItem.put("text", prompt);
        ArrayNode content = objectMapper.createArrayNode();
        content.add(contentItem);

        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "user.message");
        event.set("content", content);
        ArrayNode events = objectMapper.createArrayNode();
        events.add(event);

        ObjectNode body = objectMapper.createObjectNode();
        body.set("events", events);

        Request request = new Request.Builder()
                .url(baseUrl() + "/sessions/" + sessionId + "/events")
                .addHeader("Authorization", "Bearer " + aiConfig.getQoder().getAccessToken())
                .post(RequestBody.create(objectMapper.writeValueAsString(body), JSON_MEDIA))
                .build();
        executeForJson(request, "发送消息失败");
    }

    /**
     * 消费 Session 事件流至 session.status_idle，拼接 agent.message 文本
     *
     * @param emitter 可为 null（非流式调用时不推送）
     * @return 完整回复文本
     */
    private String consumeStream(String sessionId, SseEmitter emitter) throws IOException {
        Request request = new Request.Builder()
                .url(baseUrl() + "/sessions/" + sessionId + "/events/stream")
                .addHeader("Authorization", "Bearer " + aiConfig.getQoder().getAccessToken())
                .get()
                .build();

        StringBuilder fullContent = new StringBuilder();
        boolean idle = false;
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new BusinessException(ErrorCode.AI_PROVIDER_CALL_FAILED,
                        "Qoder 事件流拉取失败: HTTP " + response.code());
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8));
            String line;
            String currentEvent = null;
            while (!idle && (line = reader.readLine()) != null) {
                if (line.startsWith("event:")) {
                    currentEvent = line.substring(6).trim();
                    // Agent 回到 idle 即本轮结束；流此后仅剩心跳保活，直接中断避免读到超时
                    idle = "session.status_idle".equals(currentEvent);
                } else if (line.startsWith("data:")) {
                    String data = line.substring(5).trim();
                    if (!data.isEmpty() && "{}".equals(data)) {
                        // heartbeat 空数据体
                        continue;
                    }
                    handleEventData(currentEvent, data, fullContent, emitter);
                }
                // id: 行忽略（当前无需 Last-Event-ID 断点续传）
            }
        }

        if (fullContent.length() == 0) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_CALL_FAILED,
                    "Qoder Agent 未返回任何回复内容");
        }
        if (!idle) {
            log.warn("[qoder] 事件流在未收到 session.status_idle 前关闭，回复可能不完整");
        }
        return fullContent.toString();
    }

    /** 处理单条事件数据：agent.message 提取文本，status_idle 结束标记由流关闭体现 */
    private void handleEventData(String event, String data, StringBuilder fullContent, SseEmitter emitter)
            throws IOException {
        String eventType = event != null ? event : "message";
        switch (eventType) {
            case "agent.message":
                JsonNode root = objectMapper.readTree(data);
                JsonNode contentArr = root.get("content");
                if (contentArr != null && contentArr.isArray()) {
                    for (JsonNode item : contentArr) {
                        String text = item.path("text").asText("");
                        if (!text.isEmpty()) {
                            fullContent.append(text);
                            if (emitter != null) {
                                emitter.send(SseEmitter.event().name("token").data(text));
                            }
                        }
                    }
                }
                break;
            case "agent.tool_use":
            case "agent.tool_result":
                log.debug("[qoder] session 工具事件 {}: {}", eventType, data);
                break;
            case "session.status_running":
            case "agent.thinking":
            default:
                // 状态/心跳/思考类事件不产生内容
                break;
        }
    }

    /**
     * 消息列表拼接为单条 user.message 文本
     *
     * <p>格式：system 消息 →【系统指令】段；user/assistant 消息 →【历史对话】
     * 按角色标注逐轮拼接；末条 user 消息 →【当前问题】段。</p>
     */
    String buildPrompt(List<LlmClient.ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        StringBuilder history = new StringBuilder();

        // 末条 user 消息作为当前问题（约定消息列表末条必为 user）
        String currentQuestion = null;
        for (LlmClient.ChatMessage msg : messages) {
            if ("system".equals(msg.role)) {
                if (sb.length() == 0) {
                    sb.append("【系统指令】\n");
                }
                sb.append(msg.content).append("\n\n");
            } else if ("user".equals(msg.role)) {
                currentQuestion = msg.content;
            } else if ("assistant".equals(msg.role)) {
                if (history.length() > 0) {
                    history.append('\n');
                }
                history.append("用户: ").append(currentQuestion).append('\n');
                history.append("助手: ").append(msg.content);
                currentQuestion = null;
            }
        }

        if (history.length() > 0) {
            sb.append("【历史对话】\n").append(history).append("\n\n");
        }
        if (currentQuestion != null) {
            sb.append("【当前问题】\n").append(currentQuestion);
        }
        return sb.toString().trim();
    }

    /** 执行请求并解析 JSON 响应（非 2xx 抛业务异常） */
    private JsonNode executeForJson(Request request, String errorPrefix) throws IOException {
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                String errorBody = response.body() != null ? response.body().string() : "无响应体";
                throw new BusinessException(ErrorCode.AI_PROVIDER_CALL_FAILED,
                        errorPrefix + ": HTTP " + response.code() + " - " + errorBody);
            }
            return objectMapper.readTree(response.body().string());
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
