/**
 * @author HXN
 * @date 2026-09-15
 * @description 知识库对话会话管理服务
 */
package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.auth.entity.User;
import com.platform.common.exception.BusinessException;
import com.platform.common.exception.ErrorCode;
import com.platform.knowledge.dto.ChatMessageResponse;
import com.platform.knowledge.dto.ChatRequest;
import com.platform.knowledge.dto.ConversationResponse;
import com.platform.knowledge.entity.KnowledgeConversation;
import com.platform.knowledge.entity.KnowledgeMessage;
import com.platform.knowledge.mapper.KnowledgeConversationMapper;
import com.platform.knowledge.mapper.KnowledgeMessageMapper;
import com.platform.knowledge.pipeline.LlmClient;
import com.platform.knowledge.pipeline.RagPipeline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识库对话会话管理服务
 *
 * <p>负责会话 CRUD、消息管理、调用 RAG Pipeline 进行问答。</p>
 */
@Slf4j
@Service
public class KnowledgeConversationService {

    private final KnowledgeConversationMapper convMapper;
    private final KnowledgeMessageMapper msgMapper;
    private final RagPipeline ragPipeline;
    private final ObjectMapper objectMapper;

    public KnowledgeConversationService(KnowledgeConversationMapper convMapper,
                                         KnowledgeMessageMapper msgMapper,
                                         RagPipeline ragPipeline,
                                         ObjectMapper objectMapper) {
        this.convMapper = convMapper;
        this.msgMapper = msgMapper;
        this.ragPipeline = ragPipeline;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询知识库下的会话列表
     */
    public List<ConversationResponse> listConversations(Long knowledgeBaseId) {
        LambdaQueryWrapper<KnowledgeConversation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeConversation::getKnowledgeBaseId, knowledgeBaseId)
                .eq(KnowledgeConversation::getUserId, getCurrentUserId())
                .orderByDesc(KnowledgeConversation::getUpdatedAt);
        return convMapper.selectList(wrapper).stream()
                .map(this::toConvResponse)
                .collect(Collectors.toList());
    }

    /**
     * 查询会话下的消息列表
     */
    public List<ChatMessageResponse> listMessages(Long conversationId) {
        LambdaQueryWrapper<KnowledgeMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeMessage::getConversationId, conversationId)
                .orderByAsc(KnowledgeMessage::getCreatedAt);
        return msgMapper.selectList(wrapper).stream()
                .map(this::toMsgResponse)
                .collect(Collectors.toList());
    }

    /**
     * 创建新会话
     */
    @Transactional(rollbackFor = Exception.class)
    public ConversationResponse createConversation(Long knowledgeBaseId, String title) {
        KnowledgeConversation conv = new KnowledgeConversation();
        conv.setKnowledgeBaseId(knowledgeBaseId);
        conv.setTitle(title != null ? title : "新对话");
        conv.setUserId(getCurrentUserId());
        conv.setMessageCount(0);
        convMapper.insert(conv);
        return toConvResponse(conv);
    }

    /**
     * 删除会话
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteConversation(Long conversationId) {
        convMapper.deleteById(conversationId);
    }

    /**
     * 发送消息并获取 RAG 回答（流式 SSE）
     *
     * <p>事件流：meta（会话ID）→ token*（增量内容）→ sources（引用来源）→ done（完成，data为会话ID）。</p>
     */
    public SseEmitter sendMessageStream(Long knowledgeBaseId, ChatRequest request) {
        SseEmitter emitter = new SseEmitter(180000L); // 3 分钟超时

        // 获取或创建会话
        Long conversationId = request.getConversationId();
        if (conversationId == null) {
            ConversationResponse conv = createConversation(knowledgeBaseId,
                    truncate(request.getMessage(), 50));
            conversationId = conv.getId();
        }

        final Long convId = conversationId;

        // 保存用户消息
        saveMessage(convId, "user", request.getMessage(), null, null, null);

        // 构建历史消息（取最近 10 轮）
        List<LlmClient.ChatMessage> history = buildHistory(convId, 10);

        int topK = request.getTopK() != null ? request.getTopK() : 5;

        // 先发送 meta 事件告知前端会话 ID（此时未返回 emitter，数据由 Spring 缓冲后随连接发出）
        try {
            emitter.send(SseEmitter.event().name("meta").data(
                    objectMapper.writeValueAsString(
                            Collections.singletonMap("conversationId", convId))));
        } catch (Exception e) {
            log.warn("发送 meta 事件失败: convId={}", convId, e);
        }

        // 异步执行 RAG 查询
        new Thread(() -> {
            try {
                RagPipeline.RagResult result = ragPipeline.query(
                        knowledgeBaseId, request.getMessage(), history,
                        topK, request.getTemperature(), emitter);

                // 保存助手消息
                String sourcesJson = null;
                try {
                    sourcesJson = objectMapper.writeValueAsString(result.sources);
                } catch (Exception ignored) {
                }
                saveMessage(convId, "assistant", result.content, null, sourcesJson, result.durationMs);

                // 更新会话消息计数
                updateConvMessageCount(convId);

                // 消息已落库，推送引用来源与完成事件（data 为会话 ID）
                emitter.send(SseEmitter.event().name("sources")
                        .data(objectMapper.writeValueAsString(result.sources)));
                emitter.send(SseEmitter.event().name("done").data(String.valueOf(convId)));
                emitter.complete();

            } catch (Exception e) {
                log.error("RAG 查询异常: convId={}", convId, e);
                // 只推送 error 事件后正常 complete；不可用 completeWithError，
                // 否则会触发 ERROR dispatch，全局异常处理器返回 JSON 时与
                // text/event-stream 冲突，前端反而收不到错误提示
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                } catch (Exception ignored) {
                }
                emitter.complete();
            }
        }).start();

        return emitter;
    }

    private List<LlmClient.ChatMessage> buildHistory(Long conversationId, int maxRounds) {
        LambdaQueryWrapper<KnowledgeMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeMessage::getConversationId, conversationId)
                .orderByDesc(KnowledgeMessage::getCreatedAt)
                .last("LIMIT " + (maxRounds * 2));
        List<KnowledgeMessage> messages = msgMapper.selectList(wrapper);

        // 反转为时间升序
        List<LlmClient.ChatMessage> history = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            KnowledgeMessage msg = messages.get(i);
            if ("user".equals(msg.getRole()) || "assistant".equals(msg.getRole())) {
                history.add(new LlmClient.ChatMessage(msg.getRole(), msg.getContent()));
            }
        }
        return history;
    }

    private void saveMessage(Long conversationId, String role, String content,
                              Integer tokensUsed, String sourcesJson, Long durationMs) {
        KnowledgeMessage msg = new KnowledgeMessage();
        msg.setConversationId(conversationId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setTokensUsed(tokensUsed);
        msg.setSourcesJson(sourcesJson);
        msg.setDurationMs(durationMs);
        msgMapper.insert(msg);
    }

    private void updateConvMessageCount(Long conversationId) {
        KnowledgeConversation conv = convMapper.selectById(conversationId);
        if (conv == null) return;

        LambdaQueryWrapper<KnowledgeMessage> countQuery = new LambdaQueryWrapper<>();
        countQuery.eq(KnowledgeMessage::getConversationId, conversationId);
        conv.setMessageCount(msgMapper.selectCount(countQuery).intValue());
        convMapper.updateById(conv);
    }

    private ConversationResponse toConvResponse(KnowledgeConversation conv) {
        ConversationResponse resp = new ConversationResponse();
        BeanUtils.copyProperties(conv, resp);
        return resp;
    }

    private ChatMessageResponse toMsgResponse(KnowledgeMessage msg) {
        ChatMessageResponse resp = new ChatMessageResponse();
        resp.setId(msg.getId());
        resp.setConversationId(msg.getConversationId());
        resp.setRole(msg.getRole());
        resp.setContent(msg.getContent());
        resp.setTokensUsed(msg.getTokensUsed());
        resp.setDurationMs(msg.getDurationMs());
        resp.setCreatedAt(msg.getCreatedAt());

        // 解析来源
        if (msg.getSourcesJson() != null) {
            try {
                List<ChatMessageResponse.SourceItem> sources = objectMapper.readValue(
                        msg.getSourcesJson(),
                        objectMapper.getTypeFactory().constructCollectionType(
                                List.class, ChatMessageResponse.SourceItem.class));
                resp.setSources(sources);
            } catch (Exception ignored) {
            }
        }

        return resp;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "新对话";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User) {
            return ((User) auth.getPrincipal()).getId();
        }
        return null;
    }
}
