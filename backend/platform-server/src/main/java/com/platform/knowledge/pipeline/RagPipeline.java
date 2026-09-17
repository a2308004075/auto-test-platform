/**
 * @author HXN
 * @date 2026-09-15
 * @description RAG 编排引擎
 */
package com.platform.knowledge.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.knowledge.config.KnowledgeConfig;
import com.platform.knowledge.dto.ChatMessageResponse;
import com.platform.knowledge.entity.KnowledgeChunk;
import com.platform.knowledge.entity.KnowledgeDocument;
import com.platform.knowledge.mapper.KnowledgeChunkMapper;
import com.platform.knowledge.mapper.KnowledgeDocumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RAG（检索增强生成）编排引擎
 *
 * <p>完整流程：用户提问 → 向量化 → Qdrant 检索 → 上下文组装 → LLM 调用 → 流式输出。</p>
 */
@Slf4j
@Service
public class RagPipeline {

    private static final String SYSTEM_PROMPT_TEMPLATE =
            "你是一个知识库问答助手。请根据以下检索到的参考资料回答用户的问题。\n" +
            "规则：\n" +
            "1. 仅基于提供的参考资料回答，不要编造信息\n" +
            "2. 如果参考资料不足以回答问题，请明确告知用户\n" +
            "3. 回答中引用来源时请标注 [来源X]\n\n" +
            "## 参考资料\n%s";

    private final EmbeddingService embeddingService;
    private final QdrantVectorStore vectorStore;
    private final LlmClient llmClient;
    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeDocumentMapper docMapper;
    private final KnowledgeConfig config;
    private final ObjectMapper objectMapper;

    public RagPipeline(EmbeddingService embeddingService,
                        QdrantVectorStore vectorStore,
                        LlmClient llmClient,
                        KnowledgeChunkMapper chunkMapper,
                        KnowledgeDocumentMapper docMapper,
                        KnowledgeConfig config,
                        ObjectMapper objectMapper) {
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.llmClient = llmClient;
        this.chunkMapper = chunkMapper;
        this.docMapper = docMapper;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    /**
     * 流式 RAG 问答
     *
     * @param knowledgeBaseId 知识库 ID
     * @param userMessage     用户提问
     * @param history         历史消息
     * @param topK            检索分块数
     * @param temperature     温度
     * @param emitter         SSE 发送器
     * @return 问答结果（含完整回复和引用来源）
     */
    public RagResult query(Long knowledgeBaseId, String userMessage,
                           List<LlmClient.ChatMessage> history,
                           int topK, Double temperature, SseEmitter emitter) {
        long startTime = System.currentTimeMillis();

        // 1. 向量化用户问题
        List<Float> queryVector = embeddingService.embed(userMessage);

        // 2. Qdrant 检索 TopK 相关分块
        List<QdrantVectorStore.SearchHit> hits = vectorStore.search(knowledgeBaseId, queryVector, topK);

        // 3. 组装上下文
        StringBuilder contextBuilder = new StringBuilder();
        List<ChatMessageResponse.SourceItem> sources = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            QdrantVectorStore.SearchHit hit = hits.get(i);
            contextBuilder.append(String.format("[来源%d] (文档ID=%d, 分块#%d)\n%s\n\n",
                    i + 1, hit.documentId, hit.chunkIndex, hit.text));

            ChatMessageResponse.SourceItem source = new ChatMessageResponse.SourceItem();
            source.setDocName(getDocName(hit.documentId));
            source.setChunkIndex(hit.chunkIndex);
            source.setContent(truncate(hit.text, 200));
            source.setScore((double) hit.score);
            sources.add(source);
        }

        String context = contextBuilder.toString();
        String systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE,
                context.isEmpty() ? "（未检索到相关参考资料）" : context);

        // 4. 组装消息列表
        List<LlmClient.ChatMessage> messages = new ArrayList<>();
        messages.add(LlmClient.ChatMessage.system(systemPrompt));
        if (history != null) {
            messages.addAll(history);
        }
        messages.add(LlmClient.ChatMessage.user(userMessage));

        // 5. 流式调用 LLM
        String fullResponse = llmClient.chatStream(messages, temperature, emitter);

        long durationMs = System.currentTimeMillis() - startTime;
        log.info("RAG 查询完成: kbId={}, hits={}, duration={}ms", knowledgeBaseId, hits.size(), durationMs);

        return new RagResult(fullResponse, sources, durationMs);
    }

    /**
     * 非流式 RAG 问答（用于测试或简单场景）
     */
    public RagResult querySync(Long knowledgeBaseId, String userMessage,
                                List<LlmClient.ChatMessage> history,
                                int topK, Double temperature) {
        long startTime = System.currentTimeMillis();

        List<Float> queryVector = embeddingService.embed(userMessage);
        List<QdrantVectorStore.SearchHit> hits = vectorStore.search(knowledgeBaseId, queryVector, topK);

        StringBuilder contextBuilder = new StringBuilder();
        List<ChatMessageResponse.SourceItem> sources = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            QdrantVectorStore.SearchHit hit = hits.get(i);
            contextBuilder.append(String.format("[来源%d]\n%s\n\n", i + 1, hit.text));

            ChatMessageResponse.SourceItem source = new ChatMessageResponse.SourceItem();
            source.setDocName(getDocName(hit.documentId));
            source.setChunkIndex(hit.chunkIndex);
            source.setContent(truncate(hit.text, 200));
            source.setScore((double) hit.score);
            sources.add(source);
        }

        String systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE,
                contextBuilder.toString().isEmpty() ? "（未检索到相关参考资料）" : contextBuilder.toString());

        List<LlmClient.ChatMessage> messages = new ArrayList<>();
        messages.add(LlmClient.ChatMessage.system(systemPrompt));
        if (history != null) {
            messages.addAll(history);
        }
        messages.add(LlmClient.ChatMessage.user(userMessage));

        LlmClient.ChatResult result = llmClient.chat(messages, temperature);
        long durationMs = System.currentTimeMillis() - startTime;

        return new RagResult(result.content, sources, durationMs);
    }

    private String getDocName(Long documentId) {
        if (documentId == null || documentId == 0) return "未知文档";
        KnowledgeDocument doc = docMapper.selectById(documentId);
        return doc != null ? doc.getDocName() : "未知文档";
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    /**
     * RAG 查询结果
     */
    public static class RagResult {
        public final String content;
        public final List<ChatMessageResponse.SourceItem> sources;
        public final long durationMs;

        public RagResult(String content, List<ChatMessageResponse.SourceItem> sources, long durationMs) {
            this.content = content;
            this.sources = sources;
            this.durationMs = durationMs;
        }
    }
}
