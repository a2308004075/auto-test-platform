/**
 * @author HXN
 * @date 2026-09-16
 * @description 知识库资料自动同步引擎
 */
package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.knowledge.entity.KnowledgeBase;
import com.platform.knowledge.entity.KnowledgeDocument;
import com.platform.knowledge.event.ProjectMaterialChangedEvent;
import com.platform.knowledge.mapper.KnowledgeBaseMapper;
import com.platform.knowledge.mapper.KnowledgeDocumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 知识库资料自动同步引擎
 *
 * <p>监听五类项目资料的变更事件（{@link ProjectMaterialChangedEvent}），
 * 自动将变更内容同步到项目下所有知识库：内容指纹变化时重新向量化，来源消失时移除文档。</p>
 *
 * <p>同步策略：
 * <ul>
 *   <li>知识库创建后：全量同步项目下五类资料</li>
 *   <li>资料新增/修改：内容指纹（MD5）变化才重做向量化，否则跳过</li>
 *   <li>资料删除：同步移除知识库文档及向量</li>
 * </ul></p>
 */
@Slf4j
@Service
public class KnowledgeSyncService {

    private final KnowledgeBaseMapper kbMapper;
    private final KnowledgeDocumentMapper docMapper;
    private final KnowledgeDocService docService;
    private final KnowledgeMaterialCollector collector;
    /** 自身代理：syncOneMaterial 带 @Transactional 与事务提交钩子，经 self 调用才能保证代理生效 */
    private final KnowledgeSyncService self;

    public KnowledgeSyncService(KnowledgeBaseMapper kbMapper,
                                KnowledgeDocumentMapper docMapper,
                                KnowledgeDocService docService,
                                KnowledgeMaterialCollector collector,
                                @Lazy KnowledgeSyncService self) {
        this.kbMapper = kbMapper;
        this.docMapper = docMapper;
        this.docService = docService;
        this.collector = collector;
        this.self = self;
    }

    /**
     * 全量同步：采集项目下全部五类资料，写入指定知识库
     *
     * <p>知识库创建后调用。已存在同来源文档（重复创建场景）时按增量逻辑处理。</p>
     */
    public void syncAllMaterials(Long projectId, Long knowledgeBaseId) {
        KnowledgeBase kb = kbMapper.selectById(knowledgeBaseId);
        if (kb == null || !kb.getProjectId().equals(projectId)) {
            log.warn("全量同步中止，知识库不存在或不属于项目: projectId={}, kbId={}", projectId, knowledgeBaseId);
            return;
        }

        List<KnowledgeMaterialCollector.Material> materials = collector.collectAll(projectId);
        log.info("知识库全量同步开始: projectId={}, kbId={}, materials={}", projectId, knowledgeBaseId, materials.size());

        int created = 0;
        int skipped = 0;
        for (KnowledgeMaterialCollector.Material material : materials) {
            // 经 self 代理调用：this 自调用会使 @Transactional 与提交钩子失效
            if (self.syncOneMaterial(kb, material, null)) {
                created++;
            } else {
                skipped++;
            }
        }

        // 清理来源已消失的文档（如项目资料被删除后知识库仍残留）
        int removed = removeOrphanDocuments(knowledgeBaseId);

        docService.updateKbStats(knowledgeBaseId);
        log.info("知识库全量同步完成: kbId={}, created={}, skipped={}, removed={}",
                knowledgeBaseId, created, skipped, removed);
    }

    /**
     * 监听资料变更事件：同步到项目下所有知识库
     *
     * <p>事件发布位于资料 Service 的事务内，此处异步消费；采集阶段数据可能存在
     * 极短的可见性延迟，但不影响最终一致（变更会反映在下一轮指纹比对中）。</p>
     */
    @Async
    @EventListener
    public void onMaterialChanged(ProjectMaterialChangedEvent event) {
        Long projectId = event.getProjectId();
        String sourceType = event.getSourceType();
        Long sourceId = event.getSourceId();

        LambdaQueryWrapper<KnowledgeBase> kbWrapper = new LambdaQueryWrapper<>();
        kbWrapper.eq(KnowledgeBase::getProjectId, projectId);
        List<KnowledgeBase> knowledgeBases = kbMapper.selectList(kbWrapper);
        if (knowledgeBases.isEmpty()) {
            log.debug("项目下无知识库，跳过资料变更同步: projectId={}, source={}:{}",
                    projectId, sourceType, sourceId);
            return;
        }

        for (KnowledgeBase kb : knowledgeBases) {
            try {
                syncSourceToKb(kb, sourceType, sourceId);
            } catch (Exception e) {
                log.error("资料变更同步失败: kbId={}, source={}:{}", kb.getId(), sourceType, sourceId, e);
            }
        }
    }

    /**
     * 同步单个来源到指定知识库：采集 → 与既有文档比对 → 新增/更新/移除
     */
    private void syncSourceToKb(KnowledgeBase kb, String sourceType, Long sourceId) {
        KnowledgeMaterialCollector.Material material = collect(sourceType, sourceId);
        KnowledgeDocument existing = docService.findBySource(kb.getId(), sourceType, sourceId);

        if (material == null) {
            // 来源已消失（删除事件或采集失败），移除知识库文档
            if (existing != null) {
                docService.deleteDocument(kb.getId(), existing.getId());
                log.info("资料已删除，同步移除知识库文档: kbId={}, source={}:{}",
                        kb.getId(), sourceType, sourceId);
            }
            return;
        }

        // 经 self 代理调用：this 自调用会使 @Transactional 与提交钩子失效
        boolean changed = self.syncOneMaterial(kb, material, existing);
        if (!changed) {
            log.debug("资料内容未变化，跳过同步: kbId={}, source={}:{}", kb.getId(), sourceType, sourceId);
        }
    }

    /**
     * 写入/更新一份资料：内容指纹变化才重新向量化
     *
     * @return true 表示执行了写入或更新；false 表示内容未变化被跳过
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean syncOneMaterial(KnowledgeBase kb, KnowledgeMaterialCollector.Material material,
                                   KnowledgeDocument existing) {
        String newHash = md5(material.text);

        if (existing != null) {
            if (newHash.equals(existing.getContentHash())) {
                // 内容未变化，仅校正元数据（如重命名）
                boolean metaChanged = false;
                if (!material.docName.equals(existing.getDocName())) {
                    existing.setDocName(material.docName);
                    metaChanged = true;
                }
                if (metaChanged) {
                    docMapper.updateById(existing);
                }
                return false;
            }

            // 内容变化：清掉旧分块与向量，复用文档记录重新处理
            docService.deleteDocument(kb.getId(), existing.getId());
        }

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setKnowledgeBaseId(kb.getId());
        doc.setSourceType(material.sourceType);
        doc.setSourceId(material.sourceId);
        if (KnowledgeMaterialCollector.SOURCE_PROJECT_DOC.equals(material.sourceType)) {
            doc.setProjectDocId(material.sourceId);
        }
        doc.setDocName(material.docName);
        doc.setFileSize(material.fileSize);
        doc.setStatus("PENDING");
        doc.setChunkCount(0);
        doc.setContentHash(newHash);
        doc.setCreatedBy(getCurrentUserIdOrNull());
        docMapper.insert(doc);

        // 事务提交后再进入异步流水线，避免分块插入读到未提交的文档记录
        final Long docId = doc.getId();
        final Long kbId = kb.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
            @Override
            public void afterCommit() {
                KnowledgeBase freshKb = kbMapper.selectById(kbId);
                KnowledgeDocument freshDoc = docMapper.selectById(docId);
                if (freshKb != null && freshDoc != null) {
                    docService.processTextAsync(freshKb, freshDoc, material.text);
                }
            }
        });

        log.info("知识库文档写入: kbId={}, docId={}, source={}:{}, chars={}",
                kb.getId(), doc.getId(), material.sourceType, material.sourceId, material.text.length());
        return true;
    }

    /**
     * 移除来源已消失的文档：逐来源采集，采集结果为 null 且库中存在即移除
     *
     * @return 移除的文档数
     */
    private int removeOrphanDocuments(Long knowledgeBaseId) {
        LambdaQueryWrapper<KnowledgeDocument> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId);
        List<KnowledgeDocument> docs = docMapper.selectList(wrapper);

        int removed = 0;
        for (KnowledgeDocument doc : docs) {
            if (doc.getSourceType() == null || doc.getSourceId() == null) {
                continue;
            }
            KnowledgeMaterialCollector.Material material = collect(doc.getSourceType(), doc.getSourceId());
            if (material == null) {
                docService.deleteDocument(knowledgeBaseId, doc.getId());
                removed++;
            }
        }
        return removed;
    }

    /**
     * 按来源类型分发采集
     */
    private KnowledgeMaterialCollector.Material collect(String sourceType, Long sourceId) {
        switch (sourceType) {
            case KnowledgeMaterialCollector.SOURCE_REQUIREMENT:
                return collector.collectRequirementVersion(sourceId);
            case KnowledgeMaterialCollector.SOURCE_PROJECT_DOC:
                return collector.collectProjectDoc(sourceId);
            case KnowledgeMaterialCollector.SOURCE_REPOSITORY:
                return collector.collectRepository(sourceId);
            case KnowledgeMaterialCollector.SOURCE_API_MODULE:
                return collector.collectApiModule(sourceId);
            case KnowledgeMaterialCollector.SOURCE_UI_ELEMENT:
                return collector.collectUiElements(sourceId);
            default:
                log.warn("未知的资料来源类型，跳过采集: {}", sourceType);
                return null;
        }
    }

    /**
     * 计算 MD5 内容指纹
     */
    private String md5(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // MD5 为 JVM 内置算法，不会缺失；退化用原始哈希兜底
            return String.valueOf(text.hashCode());
        }
    }

    private Long getCurrentUserIdOrNull() {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof com.platform.auth.entity.User) {
            return ((com.platform.auth.entity.User) auth.getPrincipal()).getId();
        }
        return null;
    }
}