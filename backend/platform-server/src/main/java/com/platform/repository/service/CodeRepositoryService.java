/**
 * @author HXN
 * @date 2026-08-30 10:00
 * @description 测试代码仓库管理服务
 */
package com.platform.repository.service;

import cn.hutool.core.io.FileUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.common.exception.BusinessException;
import com.platform.common.exception.ErrorCode;
import com.platform.common.util.AesCryptoUtil;
import com.platform.knowledge.event.ProjectMaterialChangedEvent;
import com.platform.knowledge.service.KnowledgeMaterialCollector;
import com.platform.project.service.ProjectService;
import com.platform.repository.dto.PullLogResponse;
import com.platform.repository.dto.PullResultResponse;
import com.platform.repository.dto.RepositoryBranchListRequest;
import com.platform.repository.dto.RepositoryBranchListResponse;
import com.platform.repository.dto.RepositoryCreateRequest;
import com.platform.repository.dto.RepositoryResponse;
import com.platform.repository.dto.RepositoryUpdateRequest;
import com.platform.repository.entity.CodeRepository;
import com.platform.repository.entity.CodeRepositoryGroup;
import com.platform.repository.entity.CodeRepositoryPullLog;
import com.platform.repository.mapper.CodeRepositoryMapper;
import com.platform.repository.mapper.CodeRepositoryPullLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 测试代码仓库管理服务
 *
 * <p>提供仓库 CRUD、JGit 克隆/增量拉取、拉取历史记录能力。
 * 本地代码目录规则：{storage-path}/{projectId}/{repoId}，仓库删除时同步清理。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CodeRepositoryService {

    private final CodeRepositoryMapper repositoryMapper;
    private final CodeRepositoryPullLogMapper pullLogMapper;
    private final ProjectService projectService;
    private final CodeRepositoryGroupService repositoryGroupService;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${repository.storage-path}")
    private String storagePath;

    @Value("${repository.crypto-key}")
    private String cryptoKey;

    @Value("${repository.clone-timeout-seconds}")
    private Integer cloneTimeoutSeconds;

    /**
     * 拉取类型：首次克隆
     */
    private static final String PULL_TYPE_CLONE = "CLONE";

    /**
     * 拉取类型：增量更新
     */
    private static final String PULL_TYPE_PULL = "PULL";

    /**
     * 拉取状态：进行中
     */
    private static final String STATUS_RUNNING = "RUNNING";

    /**
     * 拉取状态：成功
     */
    private static final String STATUS_SUCCESS = "SUCCESS";

    /**
     * 拉取状态：失败
     */
    private static final String STATUS_FAILED = "FAILED";

    /**
     * Git 仓库目录标识
     */
    private static final String GIT_DIR_MARKER = ".git";

    /**
     * 本地分支引用前缀
     */
    private static final String REFS_HEADS_PREFIX = "refs/heads/";

    /**
     * HEAD 引用名（用于解析仓库默认分支）
     */
    private static final String REF_HEAD = "HEAD";

    /**
     * 拉取历史信息字段最大长度（与表字段一致）
     */
    private static final int MESSAGE_MAX_LENGTH = 2000;

    /**
     * 拉取历史默认返回条数
     */
    private static final int DEFAULT_LOG_LIMIT = 20;

    /**
     * 仓库名称最大长度（与表字段一致）
     */
    private static final int NAME_MAX_LENGTH = 50;

    /**
     * 复制副本名称后缀（首个副本）
     */
    private static final String COPY_NAME_SUFFIX = "（副本）";

    /**
     * 复制副本名称序号后缀格式（重名时：第二个及之后副本）
     */
    private static final String COPY_NAME_SEQ_FORMAT = "（副本%d）";

    /**
     * 查询项目下的仓库列表
     *
     * @param projectId 项目 ID
     * @param groupId   分组 ID（null=全部；正数=指定分组含子孙分组，含「未分组」系统分组实体 ID）
     */
    public List<RepositoryResponse> listByProject(Long projectId, Long groupId) {
        LambdaQueryWrapper<CodeRepository> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CodeRepository::getProjectId, projectId);
        if (groupId != null) {
            // 查询该分组及其所有子孙分组的仓库
            Set<Long> groupIds = repositoryGroupService.getDescendantGroupIds(groupId);
            wrapper.in(CodeRepository::getGroupId, groupIds);
        }
        wrapper.orderByDesc(CodeRepository::getCreatedAt);

        List<CodeRepository> list = repositoryMapper.selectList(wrapper);
        List<RepositoryResponse> result = new ArrayList<>();
        for (CodeRepository repo : list) {
            result.add(toResponse(repo));
        }
        return result;
    }

    /**
     * 查询项目下的仓库全量列表
     */
    public List<RepositoryResponse> listByProject(Long projectId) {
        return listByProject(projectId, null);
    }

    /**
     * 获取远程仓库分支列表（lsRemote 查询，不克隆代码）
     *
     * <p>凭证优先级：请求中的 authPassword &gt; repositoryId 对应仓库已保存凭证 &gt; 匿名访问。
     */
    public RepositoryBranchListResponse listRemoteBranches(Long projectId, RepositoryBranchListRequest request) {
        projectService.findActiveById(projectId);

        UsernamePasswordCredentialsProvider credentialsProvider = buildRequestCredentialsProvider(projectId, request);

        List<String> branches = new ArrayList<>();
        String defaultBranch = null;
        try {
            LsRemoteCommand command = Git.lsRemoteRepository()
                    .setRemote(request.getGitUrl())
                    .setTimeout(cloneTimeoutSeconds);
            if (credentialsProvider != null) {
                command.setCredentialsProvider(credentialsProvider);
            }
            for (Ref ref : command.call()) {
                String name = ref.getName();
                if (name.startsWith(REFS_HEADS_PREFIX)) {
                    branches.add(name.substring(REFS_HEADS_PREFIX.length()));
                } else if (REF_HEAD.equals(name) && ref.isSymbolic()
                        && ref.getTarget().getName().startsWith(REFS_HEADS_PREFIX)) {
                    // 服务器通告 symref 时，HEAD 指向的分支即默认分支
                    defaultBranch = ref.getTarget().getName().substring(REFS_HEADS_PREFIX.length());
                }
            }
        } catch (GitAPIException e) {
            log.warn("仓库地址 [{}] 获取远程分支失败: {}", request.getGitUrl(), e.getMessage());
            throw new BusinessException(ErrorCode.REPOSITORY_BRANCH_FETCH_FAILED,
                    "获取分支失败：" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
        Collections.sort(branches);

        RepositoryBranchListResponse response = new RepositoryBranchListResponse();
        response.setBranches(branches);
        response.setDefaultBranch(defaultBranch);
        return response;
    }

    /**
     * 解析分支查询凭证：请求密码优先，其次编辑仓库已保存凭证，均无则匿名访问
     */
    private UsernamePasswordCredentialsProvider buildRequestCredentialsProvider(Long projectId,
                                                                                RepositoryBranchListRequest request) {
        if (StringUtils.hasText(request.getAuthPassword())) {
            if (!StringUtils.hasText(request.getAuthUsername())) {
                return null;
            }
            return new UsernamePasswordCredentialsProvider(request.getAuthUsername(), request.getAuthPassword());
        }
        if (request.getRepositoryId() != null) {
            CodeRepository repo = findById(request.getRepositoryId());
            if (!repo.getProjectId().equals(projectId)) {
                throw new BusinessException(ErrorCode.REPOSITORY_NOT_FOUND,
                        "仓库不存在：" + request.getRepositoryId());
            }
            return buildCredentialsProvider(repo);
        }
        return null;
    }

    /**
     * 创建仓库（认证密码 AES 加密入库）
     */
    @Transactional(rollbackFor = Exception.class)
    public RepositoryResponse create(RepositoryCreateRequest request) {
        projectService.findActiveById(request.getProjectId());
        checkNameDuplicate(request.getProjectId(), request.getName(), null);

        CodeRepository repo = new CodeRepository();
        repo.setProjectId(request.getProjectId());
        repo.setGroupId(resolveGroupId(request.getProjectId(), request.getGroupId()));
        repo.setName(request.getName());
        repo.setGitUrl(request.getGitUrl());
        repo.setBranch(normalizeToNull(request.getBranch()));
        repo.setDescription(request.getDescription());
        repo.setAuthUsername(normalizeToNull(request.getAuthUsername()));
        if (StringUtils.hasText(request.getAuthPassword())) {
            repo.setAuthPassword(encryptPassword(request.getAuthPassword()));
        }

        repositoryMapper.insert(repo);
        return toResponse(repo);
    }

    /**
     * 更新仓库（authPassword 留空表示保持原密码不变）
     */
    @Transactional(rollbackFor = Exception.class)
    public RepositoryResponse update(Long repoId, RepositoryUpdateRequest request) {
        CodeRepository repo = findById(repoId);
        checkNameDuplicate(repo.getProjectId(), request.getName(), repoId);

        repo.setGroupId(resolveGroupId(repo.getProjectId(), request.getGroupId()));
        repo.setName(request.getName());
        repo.setGitUrl(request.getGitUrl());
        repo.setBranch(normalizeToNull(request.getBranch()));
        repo.setDescription(request.getDescription());
        repo.setAuthUsername(normalizeToNull(request.getAuthUsername()));
        if (StringUtils.hasText(request.getAuthPassword())) {
            repo.setAuthPassword(encryptPassword(request.getAuthPassword()));
        }

        repositoryMapper.updateById(repo);

        // 知识库同步：仓库元数据变化（未拉取仓库采集为空，同步引擎自动跳过）
        eventPublisher.publishEvent(new ProjectMaterialChangedEvent(
                repo.getProjectId(), KnowledgeMaterialCollector.SOURCE_REPOSITORY, repoId));

        return toResponse(repo);
    }

    /**
     * 复制仓库（一步生成副本）
     *
     * <p>名称自动追加「（副本）」后缀，重名时追加序号（（副本2）、（副本3）…）；认证凭证密文直接复制；
     * 拉取状态、本地目录与拉取历史不复制，副本保持全新未拉取状态。
     */
    @Transactional(rollbackFor = Exception.class)
    public RepositoryResponse copy(Long repoId) {
        CodeRepository source = findById(repoId);

        CodeRepository copyEntity = new CodeRepository();
        copyEntity.setProjectId(source.getProjectId());
        copyEntity.setGroupId(source.getGroupId());
        copyEntity.setName(buildCopyName(source.getProjectId(), source.getName()));
        copyEntity.setGitUrl(source.getGitUrl());
        copyEntity.setBranch(source.getBranch());
        copyEntity.setDescription(source.getDescription());
        copyEntity.setAuthUsername(source.getAuthUsername());
        copyEntity.setAuthPassword(source.getAuthPassword());

        repositoryMapper.insert(copyEntity);
        log.info("复制仓库成功: sourceRepoId={}, newRepoId={}", source.getId(), copyEntity.getId());
        return toResponse(copyEntity);
    }

    /**
     * 删除仓库（物理删除记录 + 递归删除本地代码目录，拉取历史由 FK 级联删除）
     */
    public void delete(Long repoId) {
        CodeRepository repo = findById(repoId);

        File localDir = buildLocalDir(repo.getProjectId(), repoId);
        if (localDir.exists()) {
            FileUtil.del(localDir);
            log.info("已删除仓库本地代码目录: repoId={}, path={}", repoId, localDir.getAbsolutePath());
        }

        repositoryMapper.deleteById(repoId);

        // 知识库同步：仓库删除，同步移除源代码与界面元素两类知识库文档
        // （界面元素按仓库采集，仓库删除后采集为空，同步引擎将一并移除）
        eventPublisher.publishEvent(new ProjectMaterialChangedEvent(
                repo.getProjectId(), KnowledgeMaterialCollector.SOURCE_REPOSITORY, repoId));
        eventPublisher.publishEvent(new ProjectMaterialChangedEvent(
                repo.getProjectId(), KnowledgeMaterialCollector.SOURCE_UI_ELEMENT, repoId));
    }

    /**
     * 拉取仓库代码（同步执行 + 历史记录）
     *
     * <p>本地目录不存在（或残留无效）时执行 CLONE，否则执行 PULL。
     * 拉取失败不抛异常，转为 success=false 的业务结果返回。
     */
    public PullResultResponse pull(Long repoId) {
        CodeRepository repo = findById(repoId);
        File localDir = buildLocalDir(repo.getProjectId(), repo.getId());

        boolean isClone = !isGitRepository(localDir);
        if (localDir.exists() && !isGitRepository(localDir)) {
            // 目录存在但非 Git 仓库（上次克隆失败残留），清理后重新克隆
            FileUtil.del(localDir);
        }

        // 记录拉取历史（RUNNING）
        CodeRepositoryPullLog pullLog = new CodeRepositoryPullLog();
        pullLog.setRepositoryId(repo.getId());
        pullLog.setPullType(isClone ? PULL_TYPE_CLONE : PULL_TYPE_PULL);
        pullLog.setBranch(repo.getBranch());
        pullLog.setStatus(STATUS_RUNNING);
        pullLogMapper.insert(pullLog);

        long startTime = System.currentTimeMillis();
        boolean success = false;
        String commitId = null;
        String message = null;

        try {
            UsernamePasswordCredentialsProvider credentialsProvider = buildCredentialsProvider(repo);
            if (isClone) {
                try (Git git = cloneRepository(repo, localDir, credentialsProvider)) {
                    commitId = resolveHeadCommitId(git);
                }
                message = "克隆成功";
            } else {
                try (Git git = Git.open(localDir)) {
                    // 先同步远程引用，确保配置分支的远程跟踪引用存在
                    git.fetch()
                            .setCredentialsProvider(credentialsProvider)
                            .setRemoveDeletedRefs(true)
                            .setTimeout(cloneTimeoutSeconds)
                            .call();
                    checkoutBranchIfNeeded(git, repo.getBranch());
                    git.pull()
                            .setCredentialsProvider(credentialsProvider)
                            .setTimeout(cloneTimeoutSeconds)
                            .call();
                    commitId = resolveHeadCommitId(git);
                }
                message = "拉取成功";
            }
            success = true;
        } catch (GitAPIException | IOException | BusinessException e) {
            log.warn("仓库 [{}] 拉取失败: {}", repo.getName(), e.getMessage());
            message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (isClone && localDir.exists()) {
                // 克隆失败清理残留目录，保证下次可重新克隆
                FileUtil.del(localDir);
            }
        }

        long durationMs = System.currentTimeMillis() - startTime;
        finishPullLog(pullLog, success, commitId, message, durationMs);
        updateRepositoryAfterPull(repo, success, commitId);

        // 知识库同步：代码拉取成功后重新采集仓库源码（采集粒度 = 1 仓库 = 1 知识库文档）
        if (success) {
            eventPublisher.publishEvent(new ProjectMaterialChangedEvent(
                    repo.getProjectId(), KnowledgeMaterialCollector.SOURCE_REPOSITORY, repo.getId()));
        }

        PullResultResponse response = new PullResultResponse();
        response.setLogId(pullLog.getId());
        response.setSuccess(success);
        response.setPullType(pullLog.getPullType());
        response.setBranch(repo.getBranch());
        response.setCommitId(commitId);
        response.setMessage(message);
        response.setDurationMs(durationMs);
        response.setFinishedAt(LocalDateTime.now());
        return response;
    }

    /**
     * 批量删除仓库（循环调用单条删除，同步清理本地代码目录）
     */
    @Transactional(rollbackFor = Exception.class)
    public void batchDelete(List<Long> repoIds) {
        for (Long repoId : repoIds) {
            delete(repoId);
        }
    }

    /**
     * 批量移动仓库到指定分组
     *
     * @param projectId     项目 ID（校验仓库与目标分组归属）
     * @param repoIds       仓库 ID 列表
     * @param targetGroupId 目标分组 ID（必须属于当前项目）
     */
    @Transactional(rollbackFor = Exception.class)
    public void batchMove(Long projectId, List<Long> repoIds, Long targetGroupId) {
        // 校验目标分组存在且属于当前项目
        Map<Long, CodeRepositoryGroup> groupMap = repositoryGroupService.getGroupMap(projectId);
        if (!groupMap.containsKey(targetGroupId)) {
            throw new BusinessException(ErrorCode.REPOSITORY_GROUP_NOT_FOUND, "分组不存在：" + targetGroupId);
        }
        for (Long repoId : repoIds) {
            CodeRepository repo = findById(repoId);
            if (!repo.getProjectId().equals(projectId)) {
                throw new BusinessException(ErrorCode.REPOSITORY_NOT_FOUND, "仓库不存在：" + repoId);
            }
            repo.setGroupId(targetGroupId);
            repositoryMapper.updateById(repo);
        }
    }

    /**
     * 查询仓库拉取历史（最近 20 条）
     */
    public List<PullLogResponse> listPullLogs(Long repoId) {
        findById(repoId);

        LambdaQueryWrapper<CodeRepositoryPullLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CodeRepositoryPullLog::getRepositoryId, repoId)
                .orderByDesc(CodeRepositoryPullLog::getId)
                .last("LIMIT " + DEFAULT_LOG_LIMIT);

        List<CodeRepositoryPullLog> logs = pullLogMapper.selectList(wrapper);
        List<PullLogResponse> result = new ArrayList<>();
        for (CodeRepositoryPullLog logEntry : logs) {
            result.add(toPullLogResponse(logEntry));
        }
        return result;
    }

    // ───────────────────── 私有方法 ─────────────────────

    /**
     * 解析仓库归属分组：为空时默认项目「未分组」系统分组；
     * 非空时校验分组存在且属于当前项目（防止跨项目错挂）
     */
    private Long resolveGroupId(Long projectId, Long groupId) {
        Map<Long, CodeRepositoryGroup> groupMap = repositoryGroupService.getGroupMap(projectId);
        if (groupId == null) {
            for (CodeRepositoryGroup group : groupMap.values()) {
                if (Integer.valueOf(1).equals(group.getIsSystem()) && "未分组".equals(group.getName())) {
                    return group.getId();
                }
            }
            throw new BusinessException(ErrorCode.REPOSITORY_GROUP_NOT_FOUND, "项目「未分组」系统分组缺失：" + projectId);
        }
        CodeRepositoryGroup group = groupMap.get(groupId);
        if (group == null) {
            throw new BusinessException(ErrorCode.REPOSITORY_GROUP_NOT_FOUND, "分组不存在：" + groupId);
        }
        return group.getId();
    }

    private CodeRepository findById(Long repoId) {
        CodeRepository repo = repositoryMapper.selectById(repoId);
        if (repo == null) {
            throw new BusinessException(ErrorCode.REPOSITORY_NOT_FOUND, "仓库不存在：" + repoId);
        }
        return repo;
    }

    /**
     * 校验项目内仓库名称唯一（excludeId 用于编辑时排除自身）
     */
    private void checkNameDuplicate(Long projectId, String name, Long excludeId) {
        LambdaQueryWrapper<CodeRepository> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CodeRepository::getProjectId, projectId)
                .eq(CodeRepository::getName, name);
        if (excludeId != null) {
            wrapper.ne(CodeRepository::getId, excludeId);
        }
        Long count = repositoryMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCode.REPOSITORY_NAME_DUPLICATE, "仓库名称已存在：" + name);
        }
    }

    /**
     * 生成仓库副本名称：首个追加「（副本）」，重名时依次追加「（副本2）」「（副本3）」…
     */
    private String buildCopyName(Long projectId, String sourceName) {
        int seq = 1;
        String candidate = buildSuffixedName(sourceName, buildCopySuffix(seq));
        while (isNameExists(projectId, candidate)) {
            seq++;
            candidate = buildSuffixedName(sourceName, buildCopySuffix(seq));
        }
        return candidate;
    }

    /**
     * 构建副本名称后缀：seq=1 为「（副本）」，seq>=2 为「（副本N）」
     */
    private String buildCopySuffix(int seq) {
        return seq == 1 ? COPY_NAME_SUFFIX : String.format(COPY_NAME_SEQ_FORMAT, seq);
    }

    /**
     * 原名追加后缀，超出名称最大长度时先截断原名
     */
    private String buildSuffixedName(String sourceName, String suffix) {
        if (sourceName.length() + suffix.length() <= NAME_MAX_LENGTH) {
            return sourceName + suffix;
        }
        return sourceName.substring(0, NAME_MAX_LENGTH - suffix.length()) + suffix;
    }

    /**
     * 判断项目内仓库名称是否已存在
     */
    private boolean isNameExists(Long projectId, String name) {
        LambdaQueryWrapper<CodeRepository> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CodeRepository::getProjectId, projectId)
                .eq(CodeRepository::getName, name);
        Long count = repositoryMapper.selectCount(wrapper);
        return count != null && count > 0;
    }

    /**
     * 构建本地代码目录：{storage-path}/{projectId}/{repoId}
     */
    private File buildLocalDir(Long projectId, Long repoId) {
        return new File(storagePath, projectId + File.separator + repoId);
    }

    /**
     * 判断目录是否为有效 Git 仓库（存在 .git 元数据）
     */
    private boolean isGitRepository(File dir) {
        return dir.exists() && new File(dir, GIT_DIR_MARKER).isDirectory();
    }

    /**
     * 执行克隆
     */
    private Git cloneRepository(CodeRepository repo, File localDir,
                                UsernamePasswordCredentialsProvider credentialsProvider) throws GitAPIException {
        CloneCommand command = Git.cloneRepository()
                .setURI(repo.getGitUrl())
                .setDirectory(localDir)
                .setTimeout(cloneTimeoutSeconds);
        if (StringUtils.hasText(repo.getBranch())) {
            command.setBranch(REFS_HEADS_PREFIX + repo.getBranch());
        }
        if (credentialsProvider != null) {
            command.setCredentialsProvider(credentialsProvider);
        }
        return command.call();
    }

    /**
     * 配置了分支且与当前分支不一致时切换分支（本地不存在则从远程跟踪分支创建）
     */
    private void checkoutBranchIfNeeded(Git git, String branch) throws GitAPIException, IOException {
        if (!StringUtils.hasText(branch)) {
            return;
        }
        String currentBranch = git.getRepository().getBranch();
        if (branch.equals(currentBranch)) {
            return;
        }
        git.checkout()
                .setName(branch)
                .setCreateBranch(true)
                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                .call();
    }

    /**
     * 读取当前 HEAD commit ID
     */
    private String resolveHeadCommitId(Git git) throws IOException {
        ObjectId head = git.getRepository().resolve("HEAD");
        return head != null ? head.getName() : null;
    }

    /**
     * 构建认证提供者（用户名与密码/Token 均已配置时启用）
     */
    private UsernamePasswordCredentialsProvider buildCredentialsProvider(CodeRepository repo) {
        if (!StringUtils.hasText(repo.getAuthUsername()) || !StringUtils.hasText(repo.getAuthPassword())) {
            return null;
        }
        String password = decryptPassword(repo.getAuthPassword());
        return new UsernamePasswordCredentialsProvider(repo.getAuthUsername(), password);
    }

    /**
     * 更新拉取历史为最终状态
     */
    private void finishPullLog(CodeRepositoryPullLog pullLog, boolean success,
                               String commitId, String message, long durationMs) {
        pullLog.setStatus(success ? STATUS_SUCCESS : STATUS_FAILED);
        pullLog.setCommitId(commitId);
        pullLog.setMessage(truncate(message));
        pullLog.setDurationMs(durationMs);
        pullLogMapper.updateById(pullLog);
    }

    /**
     * 拉取结束后更新仓库的最近拉取状态
     */
    private void updateRepositoryAfterPull(CodeRepository repo, boolean success, String commitId) {
        repo.setLastPullAt(LocalDateTime.now());
        repo.setLastPullStatus(success ? STATUS_SUCCESS : STATUS_FAILED);
        if (success) {
            repo.setLastCommitId(commitId);
            repo.setLocalPath(repo.getProjectId() + "/" + repo.getId());
        }
        repositoryMapper.updateById(repo);
    }

    private String encryptPassword(String plain) {
        try {
            return AesCryptoUtil.encrypt(plain, cryptoKey);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.REPOSITORY_CRYPTO_ERROR, "仓库凭证加密失败");
        }
    }

    private String decryptPassword(String cipher) {
        try {
            return AesCryptoUtil.decrypt(cipher, cryptoKey);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.REPOSITORY_CRYPTO_ERROR, "仓库凭证解密失败");
        }
    }

    /**
     * 字符串规范化：去首尾空白，空串转 null
     */
    private String normalizeToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > MESSAGE_MAX_LENGTH ? message.substring(0, MESSAGE_MAX_LENGTH) : message;
    }

    private RepositoryResponse toResponse(CodeRepository repo) {
        RepositoryResponse response = new RepositoryResponse();
        response.setId(repo.getId());
        response.setProjectId(repo.getProjectId());
        response.setGroupId(repo.getGroupId());
        response.setName(repo.getName());
        response.setGitUrl(repo.getGitUrl());
        response.setBranch(repo.getBranch());
        response.setDescription(repo.getDescription());
        response.setAuthUsername(repo.getAuthUsername());
        response.setHasAuth(StringUtils.hasText(repo.getAuthUsername()) && StringUtils.hasText(repo.getAuthPassword()));
        response.setLocalPath(repo.getLocalPath());
        response.setLastPullAt(repo.getLastPullAt());
        response.setLastPullStatus(repo.getLastPullStatus());
        response.setLastCommitId(repo.getLastCommitId());
        response.setCreatedAt(repo.getCreatedAt());
        response.setUpdatedAt(repo.getUpdatedAt());
        return response;
    }

    private PullLogResponse toPullLogResponse(CodeRepositoryPullLog logEntry) {
        PullLogResponse response = new PullLogResponse();
        response.setId(logEntry.getId());
        response.setPullType(logEntry.getPullType());
        response.setBranch(logEntry.getBranch());
        response.setStatus(logEntry.getStatus());
        response.setCommitId(logEntry.getCommitId());
        response.setMessage(logEntry.getMessage());
        response.setDurationMs(logEntry.getDurationMs());
        response.setCreatedAt(logEntry.getCreatedAt());
        return response;
    }
}
