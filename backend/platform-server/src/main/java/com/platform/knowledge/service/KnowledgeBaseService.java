/**
 * @author HXN
 * @date 2026-09-15
 * @description 知识库管理服务
 */
package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.auth.entity.User;
import com.platform.common.exception.BusinessException;
import com.platform.common.exception.ErrorCode;
import com.platform.knowledge.config.KnowledgeConfig;
import com.platform.knowledge.dto.KnowledgeBaseCreateRequest;
import com.platform.knowledge.dto.KnowledgeBaseResponse;
import com.platform.knowledge.entity.KnowledgeBase;
import com.platform.knowledge.mapper.KnowledgeBaseMapper;
import com.platform.knowledge.pipeline.QdrantVectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识库管理服务
 *
 * <p>负责知识库的创建、查询、删除。删除时同步清理 Qdrant collection。</p>
 */
@Slf4j
@Service
public class KnowledgeBaseService {

    private final KnowledgeBaseMapper kbMapper;
    private final QdrantVectorStore vectorStore;
    private final KnowledgeConfig config;
    private final KnowledgeSyncService syncService;

    public KnowledgeBaseService(KnowledgeBaseMapper kbMapper,
                                 QdrantVectorStore vectorStore,
                                 KnowledgeConfig config,
                                 KnowledgeSyncService syncService) {
        this.kbMapper = kbMapper;
        this.vectorStore = vectorStore;
        this.config = config;
        this.syncService = syncService;
    }

    /**
     * 查询项目下的知识库列表
     */
    public List<KnowledgeBaseResponse> list(Long projectId) {
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeBase::getProjectId, projectId)
                .orderByDesc(KnowledgeBase::getCreatedAt);
        return kbMapper.selectList(wrapper).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * 查询知识库详情
     */
    public KnowledgeBaseResponse getById(Long projectId, Long kbId) {
        return toResponse(findById(kbId, projectId));
    }

    /**
     * 获取项目默认知识库：存在则返回最早创建的一个，不存在则创建
     *
     * <p>智能问答菜单直达场景使用，每个项目固定一个默认知识库；
     * 创建后由 {@link #create} 自动触发五类项目资料全量同步。</p>
     *
     * <p>本方法必须开启事务：内部经 this 自调用 {@code create} 时其
     * {@code @Transactional} 代理不生效，需由外层方法提供活跃事务，
     * 否则 create 内的 TransactionSynchronization 注册会抛出
     * "Transaction synchronization is not active"。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseResponse getOrCreateDefault(Long projectId) {
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeBase::getProjectId, projectId)
                .orderByAsc(KnowledgeBase::getId)
                .last("LIMIT 1");
        KnowledgeBase existing = kbMapper.selectOne(wrapper);
        if (existing != null) {
            return toResponse(existing);
        }

        KnowledgeBaseCreateRequest request = new KnowledgeBaseCreateRequest();
        request.setName("默认知识库");
        try {
            return create(projectId, request);
        } catch (BusinessException e) {
            // 并发创建兜底：名称重复冲突时重查一次，返回已存在的默认知识库
            existing = kbMapper.selectOne(wrapper);
            if (existing != null) {
                return toResponse(existing);
            }
            throw e;
        }
    }

    /**
     * 创建知识库
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseResponse create(Long projectId, KnowledgeBaseCreateRequest request) {
        // 检查名称重复
        LambdaQueryWrapper<KnowledgeBase> nameCheck = new LambdaQueryWrapper<>();
        nameCheck.eq(KnowledgeBase::getProjectId, projectId)
                .eq(KnowledgeBase::getName, request.getName());
        if (kbMapper.selectCount(nameCheck) > 0) {
            throw new BusinessException(ErrorCode.KB_NAME_DUPLICATE,
                    "知识库名称已存在: " + request.getName());
        }

        KnowledgeBase kb = new KnowledgeBase();
        kb.setProjectId(projectId);
        kb.setName(request.getName());
        kb.setDescription(request.getDescription());
        kb.setEmbeddingModel(config.getLlm().getEmbeddingModel());
        kb.setEmbeddingDimension(config.getLlm().getEmbeddingDimension());
        kb.setDocCount(0);
        kb.setChunkCount(0);
        kb.setStatus("READY");
        kb.setCreatedBy(getCurrentUserId());
        kbMapper.insert(kb);

        // 创建 Qdrant collection
        try {
            vectorStore.ensureCollection(kb.getId(), kb.getEmbeddingDimension());
        } catch (Exception e) {
            log.warn("Qdrant collection 创建失败（知识库已创建，可后续重试）: {}", e.getMessage());
            kb.setStatus("ERROR");
            kbMapper.updateById(kb);
        }

        // 事务提交后全量同步项目下五类资料（需求文档/项目文档/源代码/接口文档/界面元素）
        final Long projectIdFinal = projectId;
        final Long kbIdFinal = kb.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
            @Override
            public void afterCommit() {
                try {
                    syncService.syncAllMaterials(projectIdFinal, kbIdFinal);
                } catch (Exception e) {
                    log.error("知识库初始化全量同步失败: kbId={}", kbIdFinal, e);
                }
            }
        });

        log.info("知识库创建成功: id={}, name={}", kb.getId(), kb.getName());
        return toResponse(kb);
    }

    /**
     * 删除知识库（含 Qdrant collection）
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long projectId, Long kbId) {
        KnowledgeBase kb = findById(kbId, projectId);
        kbMapper.deleteById(kbId);

        // 异步清理 Qdrant collection
        try {
            vectorStore.dropCollection(kbId);
        } catch (Exception e) {
            log.warn("Qdrant collection 删除失败（不影响数据库删除）: {}", e.getMessage());
        }

        log.info("知识库删除成功: id={}, name={}", kbId, kb.getName());
    }

    /**
     * 更新知识库统计信息
     */
    public void updateStats(Long kbId) {
        KnowledgeBase kb = kbMapper.selectById(kbId);
        if (kb == null) return;

        LambdaQueryWrapper<com.platform.knowledge.entity.KnowledgeDocument> docQuery = new LambdaQueryWrapper<>();
        docQuery.eq(com.platform.knowledge.entity.KnowledgeDocument::getKnowledgeBaseId, kbId);
        // 通过注入 mapper 统计，此处简化
        kbMapper.updateById(kb);
    }

    public KnowledgeBase findById(Long kbId, Long projectId) {
        KnowledgeBase kb = kbMapper.selectById(kbId);
        if (kb == null || !kb.getProjectId().equals(projectId)) {
            throw new BusinessException(ErrorCode.KB_NOT_FOUND, "知识库不存在: " + kbId);
        }
        return kb;
    }

    private KnowledgeBaseResponse toResponse(KnowledgeBase kb) {
        KnowledgeBaseResponse resp = new KnowledgeBaseResponse();
        BeanUtils.copyProperties(kb, resp);
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
