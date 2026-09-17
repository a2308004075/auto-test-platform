/**
 * @author HXN
 * @date 2026-08-30 14:00
 * @description 界面元素管理服务
 */
package com.platform.uielement.service;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.platform.common.exception.BusinessException;
import com.platform.common.exception.ErrorCode;
import com.platform.knowledge.event.ProjectMaterialChangedEvent;
import com.platform.knowledge.service.KnowledgeMaterialCollector;
import com.platform.project.service.ProjectService;
import com.platform.repository.entity.CodeRepository;
import com.platform.repository.mapper.CodeRepositoryMapper;
import com.platform.uielement.dto.UiElementFileDeleteRequest;
import com.platform.uielement.dto.UiElementFileNode;
import com.platform.uielement.dto.UiElementImportResponse;
import com.platform.uielement.dto.UiElementResponse;
import com.platform.uielement.entity.UiElement;
import com.platform.uielement.mapper.UiElementMapper;
import com.platform.uielement.parser.FrontendElementParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 界面元素管理服务
 *
 * <p>提供从已拉取仓库导入（覆盖式重建）前端源码交互元素、
 * 文件树聚合查询、元素列表查询与删除能力。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UiElementService {

    private final UiElementMapper uiElementMapper;
    private final CodeRepositoryMapper repositoryMapper;
    private final ProjectService projectService;
    private final FrontendElementParser elementParser;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${repository.storage-path}")
    private String storagePath;

    /**
     * 批量插入每批条数
     */
    private static final int BATCH_INSERT_SIZE = 500;

    /**
     * 导入界面元素（覆盖式重建：先删除同仓库旧解析结果，再全量解析插入）
     */
    @Transactional(rollbackFor = Exception.class)
    public UiElementImportResponse importFromRepository(Long projectId, Long repositoryId) {
        projectService.findActiveById(projectId);
        CodeRepository repo = findRepository(projectId, repositoryId);

        File repoDir = new File(storagePath, projectId + File.separator + repositoryId);
        if (!repoDir.isDirectory()) {
            throw new BusinessException(ErrorCode.UI_ELEMENT_IMPORT_FAILED,
                    "仓库尚未拉取代码，请先在【源代码】中拉取仓库「" + repo.getName() + "」");
        }

        long startTime = System.currentTimeMillis();
        FrontendElementParser.ParseResult parseResult = elementParser.parseRepository(repoDir, projectId, repositoryId);
        rebuildElements(repositoryId, parseResult.getElements());

        long durationMs = System.currentTimeMillis() - startTime;
        UiElementImportResponse response = new UiElementImportResponse();
        response.setRepositoryId(repo.getId());
        response.setRepositoryName(repo.getName());
        response.setFileCount(parseResult.getFileCount());
        response.setElementCount(parseResult.getElements().size());
        response.setFailedFileCount(parseResult.getFailedFileCount());
        response.setTruncated(parseResult.isTruncated());
        response.setDurationMs(durationMs);
        response.setMessage(buildSummary(parseResult));
        log.info("界面元素导入完成: projectId={}, repoId={}, files={}, elements={}, failed={}, cost={}ms",
                projectId, repositoryId, parseResult.getFileCount(), parseResult.getElements().size(),
                parseResult.getFailedFileCount(), durationMs);

        // 知识库同步：元素重建（覆盖式）后重新采集（采集粒度 = 1 仓库 = 1 知识库文档）
        eventPublisher.publishEvent(new ProjectMaterialChangedEvent(
                projectId, KnowledgeMaterialCollector.SOURCE_UI_ELEMENT, repositoryId));

        return response;
    }

    /**
     * 查询项目下的界面元素文件树（仓库 → 目录 → 文件，文件节点带元素数）
     */
    public List<UiElementFileNode> listFileTree(Long projectId) {
        QueryWrapper<UiElement> wrapper = new QueryWrapper<>();
        wrapper.select("repository_id", "file_path", "COUNT(*) AS element_count")
                .eq("project_id", projectId)
                .groupBy("repository_id", "file_path");
        List<Map<String, Object>> rows = uiElementMapper.selectMaps(wrapper);
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }

        // 仓库 ID → 名称映射
        LambdaQueryWrapper<CodeRepository> repoWrapper = new LambdaQueryWrapper<>();
        repoWrapper.eq(CodeRepository::getProjectId, projectId);
        Map<Long, String> repoNames = new HashMap<>();
        for (CodeRepository repo : repositoryMapper.selectList(repoWrapper)) {
            repoNames.put(repo.getId(), repo.getName());
        }

        return buildFileTree(rows, repoNames);
    }

    /**
     * 查询指定文件的界面元素列表（按文件内出现顺序）
     */
    public List<UiElementResponse> listElements(Long projectId, Long repositoryId, String filePath) {
        findRepository(projectId, repositoryId);

        LambdaQueryWrapper<UiElement> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UiElement::getProjectId, projectId)
                .eq(UiElement::getRepositoryId, repositoryId)
                .eq(UiElement::getFilePath, filePath)
                .orderByAsc(UiElement::getSortNo);

        List<UiElement> elements = uiElementMapper.selectList(wrapper);
        List<UiElementResponse> result = new ArrayList<>();
        for (UiElement element : elements) {
            result.add(toResponse(element));
        }
        return result;
    }

    /**
     * 删除仓库的全部界面元素
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteByRepository(Long projectId, Long repositoryId) {
        findRepository(projectId, repositoryId);
        uiElementMapper.delete(new LambdaQueryWrapper<UiElement>()
                .eq(UiElement::getProjectId, projectId)
                .eq(UiElement::getRepositoryId, repositoryId));

        // 知识库同步：元素清空后采集为空，同步引擎移除对应知识库文档
        eventPublisher.publishEvent(new ProjectMaterialChangedEvent(
                projectId, KnowledgeMaterialCollector.SOURCE_UI_ELEMENT, repositoryId));
    }

    /**
     * 删除指定文件的界面元素
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteByFile(Long projectId, UiElementFileDeleteRequest request) {
        findRepository(projectId, request.getRepositoryId());
        uiElementMapper.delete(new LambdaQueryWrapper<UiElement>()
                .eq(UiElement::getProjectId, projectId)
                .eq(UiElement::getRepositoryId, request.getRepositoryId())
                .eq(UiElement::getFilePath, request.getFilePath()));

        // 知识库同步：文件级删除归入所属仓库重新采集
        eventPublisher.publishEvent(new ProjectMaterialChangedEvent(
                projectId, KnowledgeMaterialCollector.SOURCE_UI_ELEMENT, request.getRepositoryId()));
    }

    // ───────────────────── 私有方法 ─────────────────────

    /**
     * 覆盖式重建：删除旧数据后分批批量插入
     */
    private void rebuildElements(Long repositoryId, List<UiElement> elements) {
        uiElementMapper.delete(new LambdaQueryWrapper<UiElement>()
                .eq(UiElement::getRepositoryId, repositoryId));
        if (elements.isEmpty()) {
            return;
        }
        for (List<UiElement> batch : CollUtil.split(elements, BATCH_INSERT_SIZE)) {
            uiElementMapper.insertBatch(batch);
        }
    }

    /**
     * 由聚合行构建仓库 → 目录 → 文件三级树
     */
    private List<UiElementFileNode> buildFileTree(List<Map<String, Object>> rows, Map<Long, String> repoNames) {
        List<UiElementFileNode> repoNodes = new ArrayList<>();
        Map<Long, UiElementFileNode> repoNodeIndex = new LinkedHashMap<>();
        // 目录节点索引：repoId:dirPath → node（避免遍历 children 查找）
        Map<String, UiElementFileNode> dirNodeIndex = new HashMap<>();

        for (Map<String, Object> row : rows) {
            Long repoId = ((Number) row.get("repository_id")).longValue();
            String filePath = (String) row.get("file_path");
            int elementCount = ((Number) row.get("element_count")).intValue();
            String repoName = repoNames.getOrDefault(repoId, "仓库#" + repoId);

            UiElementFileNode repoNode = repoNodeIndex.get(repoId);
            if (repoNode == null) {
                repoNode = new UiElementFileNode();
                repoNode.setNodeType("REPO");
                repoNode.setName(repoName);
                repoNode.setPath("");
                repoNode.setRepositoryId(repoId);
                repoNode.setRepositoryName(repoName);
                repoNodes.add(repoNode);
                repoNodeIndex.put(repoId, repoNode);
            }

            // 逐级创建目录节点
            UiElementFileNode parent = repoNode;
            String[] segments = filePath.split("/");
            StringBuilder currentPath = new StringBuilder();
            for (int i = 0; i < segments.length - 1; i++) {
                if (i > 0) {
                    currentPath.append('/');
                }
                currentPath.append(segments[i]);
                String key = repoId + ":" + currentPath;
                UiElementFileNode dirNode = dirNodeIndex.get(key);
                if (dirNode == null) {
                    dirNode = new UiElementFileNode();
                    dirNode.setNodeType("DIR");
                    dirNode.setName(segments[i]);
                    dirNode.setPath(currentPath.toString());
                    dirNode.setRepositoryId(repoId);
                    dirNode.setRepositoryName(repoName);
                    parent.getChildren().add(dirNode);
                    dirNodeIndex.put(key, dirNode);
                }
                parent = dirNode;
            }

            // 文件节点
            UiElementFileNode fileNode = new UiElementFileNode();
            fileNode.setNodeType("FILE");
            fileNode.setName(segments[segments.length - 1]);
            fileNode.setPath(filePath);
            fileNode.setRepositoryId(repoId);
            fileNode.setRepositoryName(repoName);
            fileNode.setElementCount(elementCount);
            parent.getChildren().add(fileNode);
        }

        // 排序：目录在前文件在后，各自按名称不区分大小写排序；仓库节点按名称排序
        for (UiElementFileNode repoNode : repoNodes) {
            sortChildren(repoNode.getChildren());
        }
        repoNodes.sort(Comparator.comparing(n -> n.getName().toLowerCase()));
        return repoNodes;
    }

    /**
     * 递归排序子节点：DIR 前置，同类型内按名称不区分大小写
     */
    private void sortChildren(List<UiElementFileNode> children) {
        children.sort(Comparator
                .comparing((UiElementFileNode n) -> "DIR".equals(n.getNodeType()) ? 0 : 1)
                .thenComparing(n -> n.getName().toLowerCase()));
        for (UiElementFileNode child : children) {
            if (!child.getChildren().isEmpty()) {
                sortChildren(child.getChildren());
            }
        }
    }

    /**
     * 校验仓库存在且属于当前项目
     */
    private CodeRepository findRepository(Long projectId, Long repositoryId) {
        CodeRepository repo = repositoryMapper.selectById(repositoryId);
        if (repo == null) {
            throw new BusinessException(ErrorCode.REPOSITORY_NOT_FOUND, "仓库不存在：" + repositoryId);
        }
        if (!repo.getProjectId().equals(projectId)) {
            throw new BusinessException(ErrorCode.REPOSITORY_NOT_FOUND, "仓库不属于当前项目：" + repositoryId);
        }
        return repo;
    }

    /**
     * 组装导入概要信息
     */
    private String buildSummary(FrontendElementParser.ParseResult parseResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("解析 ").append(parseResult.getFileCount()).append(" 个文件，提取 ")
                .append(parseResult.getElements().size()).append(" 个元素");
        if (parseResult.getFailedFileCount() > 0) {
            sb.append("，").append(parseResult.getFailedFileCount()).append(" 个文件解析失败");
        }
        if (parseResult.isTruncated()) {
            sb.append("（已达单仓库文件数上限，超出部分未解析）");
        }
        return sb.toString();
    }

    private UiElementResponse toResponse(UiElement element) {
        UiElementResponse response = new UiElementResponse();
        response.setId(element.getId());
        response.setRepositoryId(element.getRepositoryId());
        response.setFilePath(element.getFilePath());
        response.setElementTag(element.getElementTag());
        response.setElementId(element.getElementId());
        response.setElementName(element.getElementName());
        response.setElementClass(element.getElementClass());
        response.setElementText(element.getElementText());
        response.setElementPlaceholder(element.getElementPlaceholder());
        response.setElementType(element.getElementType());
        response.setSmartXPath(element.getSmartXPath());
        response.setAbsoluteXPath(element.getAbsoluteXPath());
        response.setSortNo(element.getSortNo());
        return response;
    }
}
