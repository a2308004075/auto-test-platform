/**
 * @author HXN
 * @date 2026-09-16
 * @description 知识库项目资料采集器
 */
package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.apidoc.entity.Api;
import com.platform.apidoc.mapper.ApiMapper;
import com.platform.knowledge.pipeline.DocumentParser;
import com.platform.project.entity.ApiModule;
import com.platform.project.mapper.ApiModuleMapper;
import com.platform.projectdoc.entity.ProjectDoc;
import com.platform.projectdoc.mapper.ProjectDocMapper;
import com.platform.repository.entity.CodeRepository;
import com.platform.repository.mapper.CodeRepositoryMapper;
import com.platform.requirement.entity.RequirementItem;
import com.platform.requirement.entity.RequirementVersion;
import com.platform.requirement.mapper.RequirementItemMapper;
import com.platform.requirement.mapper.RequirementVersionMapper;
import com.platform.uielement.entity.UiElement;
import com.platform.uielement.mapper.UiElementMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 项目资料采集器
 *
 * <p>将五类项目资料（需求文档/项目文档/源代码/接口文档/界面元素）提取为纯文本，
 * 供知识库同步流水线分块向量化使用。采集粒度：
 * <ul>
 *   <li>需求文档：1 个版本 = 1 份文档（版本信息 + 全部需求条目）</li>
 *   <li>项目文档：1 个文件 = 1 份文档（Tika 解析磁盘文件）</li>
 *   <li>源代码：1 个仓库 = 1 份文档（全部文本源码文件拼接，含相对路径标注）</li>
 *   <li>接口文档：1 个模块 = 1 份文档（模块信息 + 模块下全部接口定义）</li>
 *   <li>界面元素：1 个仓库 = 1 份文档（该仓库下全部界面元素清单）</li>
 * </ul></p>
 */
@Slf4j
@Component
public class KnowledgeMaterialCollector {

    /**
     * 来源类型常量：需求文档版本
     */
    public static final String SOURCE_REQUIREMENT = "REQUIREMENT_VERSION";

    /**
     * 来源类型常量：项目文档
     */
    public static final String SOURCE_PROJECT_DOC = "PROJECT_DOC";

    /**
     * 来源类型常量：源代码仓库
     */
    public static final String SOURCE_REPOSITORY = "CODE_REPOSITORY";

    /**
     * 来源类型常量：接口文档模块
     */
    public static final String SOURCE_API_MODULE = "API_MODULE";

    /**
     * 来源类型常量：界面元素仓库
     */
    public static final String SOURCE_UI_ELEMENT = "UI_ELEMENT";

    private final RequirementVersionMapper requirementVersionMapper;
    private final RequirementItemMapper requirementItemMapper;
    private final ProjectDocMapper projectDocMapper;
    private final CodeRepositoryMapper codeRepositoryMapper;
    private final ApiMapper apiMapper;
    private final ApiModuleMapper apiModuleMapper;
    private final UiElementMapper uiElementMapper;
    private final DocumentParser documentParser;

    @Value("${doc.storage-path:./data/docs}")
    private String docStoragePath;

    @Value("${repository.storage-path:./data/repos}")
    private String repoStoragePath;

    /**
     * 源码采集：单个文件大小上限（字节），超出跳过（二进制大文件误判保护）
     */
    @Value("${knowledge.sync.repo-max-file-size:1048576}")
    private long repoMaxFileSize;

    /**
     * 源码采集：单仓库文本内容总字符上限，超出截断（防止巨型仓库拖垮向量化）
     */
    @Value("${knowledge.sync.repo-max-total-chars:8000000}")
    private long repoMaxTotalChars;

    /**
     * 界面元素采集：单仓库元素行数上限，超出截断
     */
    @Value("${knowledge.sync.ui-max-elements:20000}")
    private int uiMaxElements;

    public KnowledgeMaterialCollector(RequirementVersionMapper requirementVersionMapper,
                                      RequirementItemMapper requirementItemMapper,
                                      ProjectDocMapper projectDocMapper,
                                      CodeRepositoryMapper codeRepositoryMapper,
                                      ApiMapper apiMapper,
                                      ApiModuleMapper apiModuleMapper,
                                      UiElementMapper uiElementMapper,
                                      DocumentParser documentParser) {
        this.requirementVersionMapper = requirementVersionMapper;
        this.requirementItemMapper = requirementItemMapper;
        this.projectDocMapper = projectDocMapper;
        this.codeRepositoryMapper = codeRepositoryMapper;
        this.apiMapper = apiMapper;
        this.apiModuleMapper = apiModuleMapper;
        this.uiElementMapper = uiElementMapper;
        this.documentParser = documentParser;
    }

    /**
     * 采集结果
     */
    public static class Material {

        /**
         * 来源类型（五类常量之一）
         */
        public final String sourceType;

        /**
         * 来源记录 ID
         */
        public final Long sourceId;

        /**
         * 文档显示名
         */
        public final String docName;

        /**
         * 提取的纯文本
         */
        public final String text;

        /**
         * 文件大小（字节，来源为文件时有效，其余为文本长度）
         */
        public final long fileSize;

        public Material(String sourceType, Long sourceId, String docName, String text, long fileSize) {
            this.sourceType = sourceType;
            this.sourceId = sourceId;
            this.docName = docName;
            this.text = text;
            this.fileSize = fileSize;
        }
    }

    /**
     * 采集需求文档版本（版本信息 + 全部需求条目）
     *
     * @return 采集结果；版本不存在时返回 null
     */
    public Material collectRequirementVersion(Long versionId) {
        RequirementVersion version = requirementVersionMapper.selectById(versionId);
        if (version == null) {
            return null;
        }

        LambdaQueryWrapper<RequirementItem> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RequirementItem::getVersionId, versionId)
                .orderByAsc(RequirementItem::getSortOrder)
                .orderByAsc(RequirementItem::getCreatedAt);
        List<RequirementItem> items = requirementItemMapper.selectList(wrapper);

        StringBuilder sb = new StringBuilder();
        sb.append("# 需求文档版本：").append(version.getVersionName()).append('\n');
        if (version.getDescription() != null && !version.getDescription().isEmpty()) {
            sb.append("版本描述：").append(version.getDescription()).append('\n');
        }
        sb.append("版本状态：").append(version.getStatus()).append('\n');
        if (version.getStartDate() != null) {
            sb.append("计划开始：").append(version.getStartDate()).append('\n');
        }
        if (version.getEndDate() != null) {
            sb.append("计划结束：").append(version.getEndDate()).append('\n');
        }
        sb.append('\n');

        if (items.isEmpty()) {
            sb.append("（该版本下暂无需求条目）\n");
        } else {
            sb.append("共 ").append(items.size()).append(" 个需求条目：\n\n");
            for (RequirementItem item : items) {
                sb.append("## 需求条目：").append(item.getTitle()).append('\n');
                if (item.getDescription() != null && !item.getDescription().isEmpty()) {
                    sb.append(item.getDescription()).append('\n');
                }
                sb.append("类型：").append(item.getReqType())
                        .append("，优先级：").append(item.getPriority())
                        .append("，状态：").append(item.getStatus());
                if (item.getAssignee() != null && !item.getAssignee().isEmpty()) {
                    sb.append("，负责人：").append(item.getAssignee());
                }
                if (item.getDeadline() != null) {
                    sb.append("，截止日期：").append(item.getDeadline());
                }
                sb.append("\n\n");
            }
        }

        String text = sb.toString();
        return new Material(SOURCE_REQUIREMENT, versionId,
                "需求文档-" + version.getVersionName(), text, text.length());
    }

    /**
     * 采集项目文档（Tika 解析磁盘文件）
     *
     * @return 采集结果；文档不存在或解析失败时返回 null
     */
    public Material collectProjectDoc(Long projectDocId) {
        ProjectDoc doc = projectDocMapper.selectById(projectDocId);
        if (doc == null) {
            return null;
        }

        File file = new File(new File(docStoragePath, String.valueOf(doc.getProjectId())),
                doc.getStoredName());
        if (!file.exists() || !file.isFile()) {
            log.warn("项目文档磁盘文件缺失，跳过知识库同步: projectDocId={}, path={}",
                    projectDocId, file.getAbsolutePath());
            return null;
        }

        String text;
        try {
            text = documentParser.parse(file.getAbsolutePath(), doc.getContentType());
        } catch (IOException e) {
            log.warn("项目文档解析失败，跳过知识库同步: projectDocId={}", projectDocId, e);
            return null;
        }
        if (text == null || text.trim().isEmpty()) {
            return null;
        }

        return new Material(SOURCE_PROJECT_DOC, projectDocId, doc.getDocName(), text, file.length());
    }

    /**
     * 采集源代码仓库（全部文本源码文件拼接，含相对路径标注）
     *
     * @return 采集结果；仓库不存在或尚未拉取时返回 null
     */
    public Material collectRepository(Long repositoryId) {
        CodeRepository repo = codeRepositoryMapper.selectById(repositoryId);
        if (repo == null) {
            return null;
        }

        File repoDir = new File(repoStoragePath, repo.getProjectId() + File.separator + repositoryId);
        if (!repoDir.isDirectory()) {
            // 仓库尚未拉取，不算错误：全量同步时跳过即可
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# 源代码仓库：").append(repo.getName()).append('\n');
        if (repo.getGitUrl() != null) {
            sb.append("Git 地址：").append(repo.getGitUrl()).append('\n');
        }
        if (repo.getBranch() != null) {
            sb.append("分支：").append(repo.getBranch()).append('\n');
        }
        if (repo.getDescription() != null && !repo.getDescription().isEmpty()) {
            sb.append("仓库描述：").append(repo.getDescription()).append('\n');
        }
        sb.append('\n');

        StringBuilder contentBuilder = new StringBuilder();
        try (Stream<Path> stream = Files.walk(repoDir.toPath())) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !isGitInternal(repoDir, p))
                    .sorted(Comparator.comparing(p -> p.toString().toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());

            int included = 0;
            long totalChars = 0;
            for (Path path : files) {
                File f = path.toFile();
                if (f.length() > repoMaxFileSize) {
                    continue;
                }
                String text = readFileAsUtf8IgnoreErrors(f);
                if (text == null || text.isEmpty()) {
                    continue;
                }
                String relative = repoDir.toPath().relativize(path).toString().replace(File.separatorChar, '/');
                contentBuilder.append("─── 文件：").append(relative).append(" ───\n");
                contentBuilder.append(text).append("\n\n");
                included++;
                totalChars += text.length();
                if (totalChars >= repoMaxTotalChars) {
                    log.warn("仓库源码内容达到总字符上限，截断: repoId={}, chars={}", repositoryId, totalChars);
                    contentBuilder.append("（内容已达单仓库采集上限，其余文件未收录）\n");
                    break;
                }
            }
            sb.append("共收录 ").append(included).append(" 个源码文件：\n\n");
            sb.append(contentBuilder);
        } catch (IOException e) {
            log.warn("仓库源码遍历失败，跳过知识库同步: repoId={}", repositoryId, e);
            return null;
        }

        String text = sb.toString();
        return new Material(SOURCE_REPOSITORY, repositoryId,
                "源代码-" + repo.getName(), text, text.length());
    }

    /**
     * 采集接口文档模块（模块信息 + 模块下全部接口定义）
     *
     * @return 采集结果；模块不存在时返回 null；文本为空时返回 null
     */
    public Material collectApiModule(Long moduleId) {
        ApiModule module = apiModuleMapper.selectById(moduleId);
        if (module == null) {
            return null;
        }

        LambdaQueryWrapper<Api> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Api::getModuleId, moduleId)
                .orderByAsc(Api::getCreatedAt);
        List<Api> apis = apiMapper.selectList(wrapper);

        StringBuilder sb = new StringBuilder();
        sb.append("# 接口文档模块：").append(module.getName()).append('\n');
        if (module.getDescription() != null && !module.getDescription().isEmpty()) {
            sb.append("模块描述：").append(module.getDescription()).append('\n');
        }
        if (module.getServicePrefix() != null && !module.getServicePrefix().isEmpty()) {
            sb.append("服务前缀：").append(module.getServicePrefix()).append('\n');
        }
        sb.append('\n');

        if (apis.isEmpty()) {
            sb.append("（该模块下暂无接口定义）\n");
        } else {
            sb.append("共 ").append(apis.size()).append(" 个接口：\n\n");
            for (Api api : apis) {
                sb.append("## 接口：").append(api.getName()).append('\n');
                sb.append("请求方式：").append(api.getHttpMethod()).append('\n');
                sb.append("请求路径：").append(api.getPath()).append('\n');
                if (api.getService() != null && !api.getService().isEmpty()) {
                    sb.append("所属服务：").append(api.getService()).append('\n');
                }
                if (api.getDescription() != null && !api.getDescription().isEmpty()) {
                    sb.append("接口描述：").append(api.getDescription()).append('\n');
                }
                if (api.getContentType() != null && !api.getContentType().isEmpty()) {
                    sb.append("Content-Type：").append(api.getContentType()).append('\n');
                }
                appendJsonField(sb, "请求参数", api.getRequestParams());
                appendJsonField(sb, "请求头", api.getHeaders());
                appendJsonField(sb, "请求体", api.getRequestBody());
                appendJsonField(sb, "响应体", api.getResponseBody());
                sb.append('\n');
            }
        }

        String text = sb.toString();
        return new Material(SOURCE_API_MODULE, moduleId,
                "接口文档-" + module.getName(), text, text.length());
    }

    /**
     * 采集界面元素（仓库下全部界面元素清单）
     *
     * @return 采集结果；仓库不存在或无元素时返回 null
     */
    public Material collectUiElements(Long repositoryId) {
        CodeRepository repo = codeRepositoryMapper.selectById(repositoryId);
        if (repo == null) {
            return null;
        }

        LambdaQueryWrapper<UiElement> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UiElement::getRepositoryId, repositoryId)
                .orderByAsc(UiElement::getFilePath)
                .orderByAsc(UiElement::getSortNo)
                .last("LIMIT " + (uiMaxElements + 1));
        List<UiElement> elements = uiElementMapper.selectList(wrapper);
        if (elements.isEmpty()) {
            return null;
        }

        boolean truncated = elements.size() > uiMaxElements;
        if (truncated) {
            elements = new ArrayList<>(elements.subList(0, uiMaxElements));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# 界面元素清单：").append(repo.getName()).append('\n');
        sb.append("共 ").append(elements.size()).append(" 个界面元素");
        if (truncated) {
            sb.append("（已达采集上限，超出部分未收录）");
        }
        sb.append("\n\n");

        String currentFile = null;
        for (UiElement element : elements) {
            if (!element.getFilePath().equals(currentFile)) {
                currentFile = element.getFilePath();
                sb.append("## 文件：").append(currentFile).append('\n');
            }
            sb.append("- 元素：<").append(element.getElementTag()).append('>');
            if (element.getElementId() != null && !element.getElementId().isEmpty()) {
                sb.append(" id=\"").append(element.getElementId()).append('"');
            }
            if (element.getElementName() != null && !element.getElementName().isEmpty()) {
                sb.append(" name=\"").append(element.getElementName()).append('"');
            }
            if (element.getElementPlaceholder() != null && !element.getElementPlaceholder().isEmpty()) {
                sb.append(" placeholder=\"").append(element.getElementPlaceholder()).append('"');
            }
            if (element.getElementType() != null && !element.getElementType().isEmpty()) {
                sb.append(" type=\"").append(element.getElementType()).append('"');
            }
            if (element.getElementText() != null && !element.getElementText().isEmpty()) {
                sb.append(" 文本=\"").append(element.getElementText()).append('"');
            }
            sb.append('\n');
            sb.append("  XPath：").append(element.getSmartXPath()).append('\n');
        }

        String text = sb.toString();
        return new Material(SOURCE_UI_ELEMENT, repositoryId,
                "界面元素-" + repo.getName(), text, text.length());
    }

    /**
     * 采集项目下全部五类资料（知识库创建后 / 全量同步使用）
     *
     * <p>源代码仓库仅采集已成功拉取过代码的仓库（localPath 非空）。</p>
     */
    public List<Material> collectAll(Long projectId) {
        List<Material> materials = new ArrayList<>();

        // 1. 需求文档：全部版本
        LambdaQueryWrapper<RequirementVersion> versionWrapper = new LambdaQueryWrapper<>();
        versionWrapper.eq(RequirementVersion::getProjectId, projectId)
                .orderByAsc(RequirementVersion::getCreatedAt);
        for (RequirementVersion version : requirementVersionMapper.selectList(versionWrapper)) {
            addIfNotNull(materials, collectRequirementVersion(version.getId()));
        }

        // 2. 项目文档：全部文档
        LambdaQueryWrapper<ProjectDoc> docWrapper = new LambdaQueryWrapper<>();
        docWrapper.eq(ProjectDoc::getProjectId, projectId)
                .orderByAsc(ProjectDoc::getCreatedAt);
        for (ProjectDoc doc : projectDocMapper.selectList(docWrapper)) {
            addIfNotNull(materials, collectProjectDoc(doc.getId()));
        }

        // 3. 源代码：全部已拉取仓库
        LambdaQueryWrapper<CodeRepository> repoWrapper = new LambdaQueryWrapper<>();
        repoWrapper.eq(CodeRepository::getProjectId, projectId)
                .isNotNull(CodeRepository::getLocalPath)
                .orderByAsc(CodeRepository::getCreatedAt);
        for (CodeRepository repo : codeRepositoryMapper.selectList(repoWrapper)) {
            addIfNotNull(materials, collectRepository(repo.getId()));
        }

        // 4. 接口文档：全部模块
        LambdaQueryWrapper<ApiModule> moduleWrapper = new LambdaQueryWrapper<>();
        moduleWrapper.eq(ApiModule::getProjectId, projectId)
                .orderByAsc(ApiModule::getCreatedAt);
        for (ApiModule module : apiModuleMapper.selectList(moduleWrapper)) {
            addIfNotNull(materials, collectApiModule(module.getId()));
        }

        // 5. 界面元素：全部有元素的仓库
        LambdaQueryWrapper<UiElement> elementWrapper = new LambdaQueryWrapper<>();
        elementWrapper.eq(UiElement::getProjectId, projectId);
        List<UiElement> allElements = uiElementMapper.selectList(elementWrapper);
        if (!allElements.isEmpty()) {
            List<Long> repoIds = allElements.stream()
                    .map(UiElement::getRepositoryId)
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());
            for (Long repoId : repoIds) {
                addIfNotNull(materials, collectUiElements(repoId));
            }
        }

        return materials;
    }

    // ───────────────────── 私有方法 ─────────────────────

    private void addIfNotNull(List<Material> materials, Material material) {
        if (material != null) {
            materials.add(material);
        }
    }

    /**
     * 判断文件是否位于 .git 内部目录
     */
    private boolean isGitInternal(File repoDir, Path path) {
        String relative = repoDir.toPath().relativize(path).toString().replace(File.separatorChar, '/');
        return relative.startsWith(".git/") || relative.equals(".git");
    }

    /**
     * 以 UTF-8 读取文本文件（解码错误字节忽略），非文本或读取失败返回 null
     */
    private String readFileAsUtf8IgnoreErrors(File file) {
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            // UTF-8 BOM 去除
            int offset = 0;
            if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                    && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
                offset = 3;
            }
            // 二进制误判保护：NUL 字节出现即视为二进制
            for (int i = offset; i < bytes.length; i++) {
                if (bytes[i] == 0) {
                    return null;
                }
            }
            return new String(Arrays.copyOfRange(bytes, offset, bytes.length), StandardCharsets.UTF_8);
        } catch (IOException | OutOfMemoryError e) {
            return null;
        }
    }

    /**
     * 追加 JSON 字段（截断超长内容，避免单接口撑爆上下文）
     */
    private void appendJsonField(StringBuilder sb, String label, String json) {
        if (json == null || json.trim().isEmpty()) {
            return;
        }
        String value = json.trim();
        if (value.length() > 4000) {
            value = value.substring(0, 4000) + "…（内容过长已截断）";
        }
        sb.append(label).append("：").append(value).append('\n');
    }
}
