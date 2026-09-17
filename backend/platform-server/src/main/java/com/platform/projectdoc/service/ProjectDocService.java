/**
 * @author HXN
 * @date 2026-08-30
 * @description 项目文档管理服务
 */
package com.platform.projectdoc.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.auth.entity.User;
import com.platform.common.exception.BusinessException;
import com.platform.common.exception.ErrorCode;
import com.platform.common.response.PageResponse;
import com.platform.knowledge.event.ProjectMaterialChangedEvent;
import com.platform.knowledge.service.KnowledgeMaterialCollector;
import com.platform.project.service.ProjectService;
import com.platform.projectdoc.dto.ProjectDocResponse;
import com.platform.projectdoc.dto.ProjectDocUpdateRequest;
import com.platform.projectdoc.entity.ProjectDoc;
import com.platform.projectdoc.entity.ProjectDocGroup;
import com.platform.projectdoc.mapper.ProjectDocGroupMapper;
import com.platform.projectdoc.mapper.ProjectDocMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.*;

/**
 * 项目文档管理服务
 *
 * <p>文件本体存于本地磁盘（doc.storage-path，按项目分目录），数据库仅存元数据。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectDocService {

    private final ProjectDocMapper projectDocMapper;
    private final ProjectDocGroupMapper projectDocGroupMapper;
    private final ProjectDocGroupService projectDocGroupService;
    private final ProjectService projectService;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${doc.storage-path:./data/docs}")
    private String storagePath;

    /**
     * 分页查询项目文档列表
     * <p>groupId 为空 = 全部；groupId == 0 = 未分组；正数 = 指定分组含子孙分组。
     */
    public PageResponse<ProjectDocResponse> list(Long projectId, Long groupId, String keyword,
                                                 int page, int pageSize) {
        LambdaQueryWrapper<ProjectDoc> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ProjectDoc::getProjectId, projectId);
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(ProjectDoc::getDocName, keyword)
                    .or().like(ProjectDoc::getFileName, keyword));
        }
        if (groupId != null) {
            if (groupId == 0 || isUngroupedSystemGroup(groupId)) {
                // 0 = 未分组（前端语义约定）；传入"未分组"系统分组真实 ID 同样查 isNull
                wrapper.isNull(ProjectDoc::getGroupId);
            } else {
                // 正数 = 指定分组含子分组
                Set<Long> idSet = projectDocGroupService.getDescendantGroupIds(groupId);
                wrapper.in(ProjectDoc::getGroupId, idSet);
            }
        }
        wrapper.orderByDesc(ProjectDoc::getCreatedAt);

        Page<ProjectDoc> pageParam = new Page<>(page, pageSize);
        Page<ProjectDoc> result = projectDocMapper.selectPage(pageParam, wrapper);

        List<ProjectDocResponse> records = new ArrayList<>();
        for (ProjectDoc doc : result.getRecords()) {
            records.add(toResponse(doc));
        }
        return PageResponse.of(records, result.getTotal(), page, pageSize);
    }

    /**
     * 上传文档
     * <p>storedName = UUID + 原扩展名；先写磁盘再入库，入库失败回滚时删除已写文件。
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectDocResponse upload(Long projectId, Long groupId, String docName, String description,
                                     MultipartFile file) {
        projectService.findActiveById(projectId);

        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "上传文件不能为空");
        }
        // groupId=0 表示未分组，归一化为 NULL
        Long targetGroupId = (groupId != null && groupId == 0) ? null : groupId;
        if (targetGroupId != null) {
            validateGroupBelongsToProject(targetGroupId, projectId);
        }

        String originalName = StringUtils.hasText(file.getOriginalFilename())
                ? file.getOriginalFilename() : "unnamed";
        String extension = extractExtension(originalName);
        String storedName = UUID.randomUUID().toString().replace("-", "") + extension;

        ProjectDoc doc = new ProjectDoc();
        doc.setProjectId(projectId);
        doc.setGroupId(targetGroupId);
        doc.setDocName(StringUtils.hasText(docName) ? docName : originalName);
        doc.setFileName(originalName);
        doc.setStoredName(storedName);
        doc.setFileSize(file.getSize());
        doc.setContentType(file.getContentType());
        doc.setDescription(description);
        doc.setCreatedBy(getCurrentUserId());

        File target = new File(getProjectDir(projectId), storedName);
        try {
            // 先写磁盘再入库；入库抛异常时删除已写文件，避免产生孤儿文件
            file.transferTo(target.getAbsoluteFile());
            projectDocMapper.insert(doc);
        } catch (IOException e) {
            log.error("文档上传写盘失败: projectId={}, storedName={}", projectId, storedName, e);
            throw new BusinessException(ErrorCode.PROJECT_DOC_FILE_ERROR, "文件保存失败：" + e.getMessage());
        } catch (RuntimeException e) {
            if (target.exists() && !target.delete()) {
                log.warn("文档入库失败后清理磁盘文件失败: {}", target.getAbsolutePath());
            }
            throw e;
        }

        // 知识库同步：新上传文档（采集粒度 = 1 文件 = 1 知识库文档）
        eventPublisher.publishEvent(new ProjectMaterialChangedEvent(
                projectId, KnowledgeMaterialCollector.SOURCE_PROJECT_DOC, doc.getId()));

        return toResponse(doc);
    }

    /**
     * 更新文档（重命名/描述/移动分组）
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectDocResponse update(Long docId, ProjectDocUpdateRequest request) {
        ProjectDoc doc = findById(docId);

        if (StringUtils.hasText(request.getDocName())) {
            doc.setDocName(request.getDocName());
        }
        if (request.getDescription() != null) {
            doc.setDescription(request.getDescription());
        }
        if (request.getGroupId() != null) {
            if (request.getGroupId() == 0) {
                // 0 = 移入未分组
                doc.setGroupId(null);
            } else {
                validateGroupBelongsToProject(request.getGroupId(), doc.getProjectId());
                doc.setGroupId(request.getGroupId());
            }
        }

        projectDocMapper.updateById(doc);

        // 知识库同步：元数据（文档名等）变化，采集器会重新比对指纹
        eventPublisher.publishEvent(new ProjectMaterialChangedEvent(
                doc.getProjectId(), KnowledgeMaterialCollector.SOURCE_PROJECT_DOC, docId));

        return toResponse(doc);
    }

    /**
     * 替换文档文件（元数据不变，仅更换磁盘文件本体）
     * <p>先写新文件 → 更新 DB 四个文件字段 → 删除旧磁盘文件。
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectDocResponse replace(Long docId, MultipartFile file) {
        ProjectDoc doc = findById(docId);

        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "替换文件不能为空");
        }

        String originalName = StringUtils.hasText(file.getOriginalFilename())
                ? file.getOriginalFilename() : doc.getFileName();
        String extension = extractExtension(originalName);
        String newStoredName = UUID.randomUUID().toString().replace("-", "") + extension;

        File newFile = new File(getProjectDir(doc.getProjectId()), newStoredName);
        try {
            file.transferTo(newFile.getAbsoluteFile());
        } catch (IOException e) {
            log.error("文档替换写盘失败: docId={}, storedName={}", docId, newStoredName, e);
            throw new BusinessException(ErrorCode.PROJECT_DOC_FILE_ERROR, "文件保存失败：" + e.getMessage());
        }

        String oldStoredName = doc.getStoredName();
        doc.setFileName(originalName);
        doc.setStoredName(newStoredName);
        doc.setFileSize(file.getSize());
        doc.setContentType(file.getContentType());

        try {
            projectDocMapper.updateById(doc);
        } catch (RuntimeException e) {
            if (newFile.exists() && !newFile.delete()) {
                log.warn("文档替换入库失败后清理新文件失败: {}", newFile.getAbsolutePath());
            }
            throw e;
        }

        // DB 更新成功后删除旧文件（失败仅告警，不影响事务）
        deleteQuietly(new File(getProjectDir(doc.getProjectId()), oldStoredName));

        // 知识库同步：文件本体更换，内容必变，触发重新采集
        eventPublisher.publishEvent(new ProjectMaterialChangedEvent(
                doc.getProjectId(), KnowledgeMaterialCollector.SOURCE_PROJECT_DOC, docId));

        return toResponse(doc);
    }

    /**
     * 下载文档
     * <p>下载名 = docName（无扩展名时补 fileName 的扩展名）。
     */
    public void download(Long docId, HttpServletResponse response) {
        ProjectDoc doc = findById(docId);

        File file = new File(getProjectDir(doc.getProjectId()), doc.getStoredName());
        if (!file.exists() || !file.isFile()) {
            throw new BusinessException(ErrorCode.PROJECT_DOC_FILE_ERROR, "文件不存在或已被删除：" + doc.getStoredName());
        }

        String downloadName = doc.getDocName();
        String docExt = extractExtension(doc.getDocName());
        String fileExt = extractExtension(doc.getFileName());
        if (docExt.isEmpty() && !fileExt.isEmpty()) {
            downloadName = downloadName + fileExt;
        }

        response.setContentType(StringUtils.hasText(doc.getContentType())
                ? doc.getContentType() : "application/octet-stream");
        try {
            response.setHeader("Content-Disposition",
                    "attachment;filename=" + URLEncoder.encode(downloadName, "UTF-8"));
            response.setContentLengthLong(file.length());

            byte[] buffer = new byte[8192];
            try (InputStream in = new java.io.FileInputStream(file);
                 OutputStream out = response.getOutputStream()) {
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
            }
        } catch (IOException e) {
            log.error("文档下载失败: docId={}", docId, e);
            throw new BusinessException(ErrorCode.PROJECT_DOC_FILE_ERROR, "文件下载失败：" + e.getMessage());
        }
    }

    /**
     * 删除文档（DB 记录 + 磁盘文件）
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long docId) {
        ProjectDoc doc = findById(docId);

        projectDocMapper.deleteById(docId);

        // 磁盘文件删除失败仅告警，不影响事务
        deleteQuietly(new File(getProjectDir(doc.getProjectId()), doc.getStoredName()));

        // 知识库同步：文档删除，同步引擎采集不到将移除对应知识库文档
        eventPublisher.publishEvent(new ProjectMaterialChangedEvent(
                doc.getProjectId(), KnowledgeMaterialCollector.SOURCE_PROJECT_DOC, docId));
    }

    // ───────────────────── 私有方法 ─────────────────────

    /**
     * 校验分组归属当前项目（且分组存在）
     */
    private void validateGroupBelongsToProject(Long groupId, Long projectId) {
        ProjectDocGroup group = projectDocGroupMapper.selectById(groupId);
        if (group == null) {
            throw new BusinessException(ErrorCode.PROJECT_DOC_GROUP_NOT_FOUND, "分组不存在：" + groupId);
        }
        if (!group.getProjectId().equals(projectId)) {
            throw new BusinessException(ErrorCode.PROJECT_DOC_GROUP_NOT_FOUND, "分组不属于当前项目：" + groupId);
        }
    }

    /**
     * 判断分组 ID 是否为该项目"未分组"系统分组
     * <p>未分组文档的 groupId 为 NULL，点击"未分组"树节点传真实分组 ID 时需转 isNull 查询。
     */
    private boolean isUngroupedSystemGroup(Long groupId) {
        ProjectDocGroup group = projectDocGroupMapper.selectById(groupId);
        return group != null
                && Integer.valueOf(1).equals(group.getIsSystem())
                && "未分组".equals(group.getName());
    }

    /**
     * 获取项目文档存储目录（不存在时自动创建）
     */
    private File getProjectDir(Long projectId) {
        File dir = new File(storagePath, String.valueOf(projectId));
        if (!dir.exists() && !dir.mkdirs()) {
            throw new BusinessException(ErrorCode.PROJECT_DOC_FILE_ERROR,
                    "文档存储目录创建失败：" + dir.getAbsolutePath());
        }
        return dir;
    }

    /**
     * 提取文件扩展名（含点，如 ".txt"；无扩展名返回空串）
     */
    private String extractExtension(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "";
        }
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(idx);
    }

    private void deleteQuietly(File file) {
        if (file.exists() && !file.delete()) {
            log.warn("磁盘文件删除失败: {}", file.getAbsolutePath());
        }
    }

    private ProjectDoc findById(Long docId) {
        ProjectDoc doc = projectDocMapper.selectById(docId);
        if (doc == null) {
            throw new BusinessException(ErrorCode.PROJECT_DOC_NOT_FOUND, "文档不存在：" + docId);
        }
        return doc;
    }

    private ProjectDocResponse toResponse(ProjectDoc doc) {
        ProjectDocResponse resp = new ProjectDocResponse();
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
