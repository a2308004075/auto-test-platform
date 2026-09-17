/**
 * @author HXN
 * @date 2026-09-15
 * @description 知识库文档管理服务
 */
package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.auth.entity.User;
import com.platform.common.exception.BusinessException;
import com.platform.common.exception.ErrorCode;
import com.platform.knowledge.config.KnowledgeConfig;
import com.platform.knowledge.dto.KnowledgeDocumentResponse;
import com.platform.knowledge.entity.KnowledgeBase;
import com.platform.knowledge.entity.KnowledgeChunk;
import com.platform.knowledge.entity.KnowledgeDocument;
import com.platform.knowledge.mapper.KnowledgeBaseMapper;
import com.platform.knowledge.mapper.KnowledgeChunkMapper;
import com.platform.knowledge.mapper.KnowledgeDocumentMapper;
import com.platform.knowledge.pipeline.EmbeddingService;
import com.platform.knowledge.pipeline.QdrantVectorStore;
import com.platform.knowledge.pipeline.TextChunker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 知识库文档管理服务
 *
 * <p>负责知识库文档的查询、删除，以及通用的文本处理流水线（分块 → 向量化 → Qdrant 存储）。
 * 文档来源由 {@link KnowledgeSyncService} 自动同步五类项目资料，不再支持手动导入。</p>
 */
@Slf4j
@Service
public class KnowledgeDocService {

    private final KnowledgeDocumentMapper docMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final TextChunker textChunker;
    private final EmbeddingService embeddingService;
    private final QdrantVectorStore vectorStore;
    private final KnowledgeConfig config;

    public KnowledgeDocService(KnowledgeDocumentMapper docMapper,
                                KnowledgeChunkMapper chunkMapper,
                                KnowledgeBaseMapper kbMapper,
                                TextChunker textChunker,
                                EmbeddingService embeddingService,
                                QdrantVectorStore vectorStore,
                                KnowledgeConfig config) {
        this.docMapper = docMapper;
        this.chunkMapper = chunkMapper;
        this.kbMapper = kbMapper;
        this.textChunker = textChunker;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.config = config;
    }

    /**
     * 查询知识库下的文档列表
     */
    public List<KnowledgeDocumentResponse> listDocuments(Long knowledgeBaseId) {
        LambdaQueryWrapper<KnowledgeDocument> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId)
                .orderByDesc(KnowledgeDocument::getCreatedAt);
        return docMapper.selectList(wrapper).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * 删除知识库文档（含分块和 Qdrant 向量）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDocument(Long knowledgeBaseId, Long docId) {
        KnowledgeDocument doc = docMapper.selectById(docId);
        if (doc == null || !doc.getKnowledgeBaseId().equals(knowledgeBaseId)) {
            throw new BusinessException(ErrorCode.KB_DOC_NOT_FOUND, "知识库文档不存在");
        }

        // 删除 Qdrant 向量
        vectorStore.deleteByDocumentId(knowledgeBaseId, docId);

        // 数据库删除（分块通过外键级联删除）
        docMapper.deleteById(docId);

        // 更新知识库统计
        updateKbStats(knowledgeBaseId);

        log.info("知识库文档删除: kbId={}, docId={}", knowledgeBaseId, docId);
    }

    /**
     * 查询知识库内指定来源的文档（同步引擎去重判断用）
     *
     * @return 匹配的文档；不存在返回 null
     */
    public KnowledgeDocument findBySource(Long knowledgeBaseId, String sourceType, Long sourceId) {
        LambdaQueryWrapper<KnowledgeDocument> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId)
                .eq(KnowledgeDocument::getSourceType, sourceType)
                .eq(KnowledgeDocument::getSourceId, sourceId);
        return docMapper.selectOne(wrapper);
    }

    /**
     * 异步处理文本：分块 → 向量化 → 存储
     *
     * <p>由同步引擎在写入/更新文档记录后调用。文本为空或处理失败时将文档置为 ERROR。</p>
     *
     * @param kb   知识库
     * @param doc  知识库文档记录（须已持久化，携带 id）
     * @param text 待处理纯文本
     */
    public void processTextAsync(KnowledgeBase kb, KnowledgeDocument doc, String text) {
        CompletableFuture.runAsync(() -> {
            try {
                doc.setStatus("PROCESSING");
                docMapper.updateById(doc);

                // 更新知识库状态
                kb.setStatus("PROCESSING");
                kbMapper.updateById(kb);

                if (text == null || text.trim().isEmpty()) {
                    doc.setStatus("ERROR");
                    doc.setErrorMessage("文档内容为空");
                    docMapper.updateById(doc);
                    return;
                }

                // 1. 文本分块
                List<TextChunker.ChunkResult> chunks = textChunker.split(text);
                log.info("文档分块完成: docId={}, chunks={}", doc.getId(), chunks.size());

                // 2. 批量嵌入（每批最多 20 条）+ 3. 保存分块并准备向量条目
                List<String> chunkTexts = new ArrayList<>();
                for (TextChunker.ChunkResult chunk : chunks) {
                    chunkTexts.add(chunk.content);
                }

                List<QdrantVectorStore.VectorEntry> entries = new ArrayList<>();
                int batchSize = 20;
                for (int i = 0; i < chunkTexts.size(); i += batchSize) {
                    int end = Math.min(i + batchSize, chunkTexts.size());
                    List<String> batch = chunkTexts.subList(i, end);
                    List<List<Float>> vectors = embeddingService.embedBatch(batch);

                    for (int j = 0; j < batch.size(); j++) {
                        int chunkIdx = i + j;
                        TextChunker.ChunkResult chunk = chunks.get(chunkIdx);

                        // 保存分块到数据库
                        KnowledgeChunk kbChunk = new KnowledgeChunk();
                        kbChunk.setDocumentId(doc.getId());
                        kbChunk.setChunkIndex(chunk.index);
                        kbChunk.setContent(chunk.content);
                        kbChunk.setTokenCount(chunk.tokenCount);
                        kbChunk.setStatus("PENDING");
                        chunkMapper.insert(kbChunk);

                        // 准备 Qdrant 插入
                        QdrantVectorStore.VectorEntry entry = new QdrantVectorStore.VectorEntry();
                        entry.vectorId = UUID.randomUUID().toString().replace("-", "");
                        entry.documentId = doc.getId();
                        entry.chunkIndex = chunk.index;
                        entry.text = chunk.content;
                        entry.vector = vectors.get(j);
                        entries.add(entry);

                        // 更新分块 vectorId
                        kbChunk.setVectorId(entry.vectorId);
                        kbChunk.setStatus("VECTORIZED");
                        chunkMapper.updateById(kbChunk);
                    }
                }

                // 4. 批量插入 Qdrant
                if (!entries.isEmpty()) {
                    vectorStore.insertVectors(kb.getId(), entries);
                }

                // 5. 更新文档状态
                doc.setStatus("COMPLETED");
                doc.setChunkCount(chunks.size());
                docMapper.updateById(doc);

                // 6. 更新知识库统计
                updateKbStats(kb.getId());

                log.info("文档处理完成: docId={}, chunks={}", doc.getId(), chunks.size());

            } catch (Exception e) {
                log.error("文档处理失败: docId={}", doc.getId(), e);
                doc.setStatus("ERROR");
                doc.setErrorMessage(e.getMessage());
                docMapper.updateById(doc);

                kb.setStatus("ERROR");
                kbMapper.updateById(kb);
            }
        });
    }

    /**
     * 更新知识库统计信息
     */
    public void updateKbStats(Long kbId) {
        KnowledgeBase kb = kbMapper.selectById(kbId);
        if (kb == null) return;

        LambdaQueryWrapper<KnowledgeDocument> docQuery = new LambdaQueryWrapper<>();
        docQuery.eq(KnowledgeDocument::getKnowledgeBaseId, kbId);
        int docCount = docMapper.selectCount(docQuery).intValue();
        kb.setDocCount(docCount);

        // 统计分块总数（简化：遍历文档统计）
        List<KnowledgeDocument> docs = docMapper.selectList(docQuery);
        int totalChunks = 0;
        for (KnowledgeDocument d : docs) {
            totalChunks += (d.getChunkCount() != null ? d.getChunkCount() : 0);
        }
        kb.setChunkCount(totalChunks);

        // 检查是否还有处理中的文档
        LambdaQueryWrapper<KnowledgeDocument> processingQuery = new LambdaQueryWrapper<>();
        processingQuery.eq(KnowledgeDocument::getKnowledgeBaseId, kbId)
                .in(KnowledgeDocument::getStatus, "PENDING", "PROCESSING");
        if (docMapper.selectCount(processingQuery) > 0) {
            kb.setStatus("PROCESSING");
        } else {
            kb.setStatus("READY");
        }

        kbMapper.updateById(kb);
    }

    private KnowledgeDocumentResponse toResponse(KnowledgeDocument doc) {
        KnowledgeDocumentResponse resp = new KnowledgeDocumentResponse();
        BeanUtils.copyProperties(doc, resp);
        return resp;
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User) {
            return ((User) auth.getPrincipal()).getId();
        }
        return null;
    }
}

