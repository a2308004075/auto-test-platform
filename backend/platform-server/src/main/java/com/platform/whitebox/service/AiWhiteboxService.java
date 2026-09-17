/**
 * @author HXN
 * @date 2026-09-15
 * @description AI 白盒测试业务服务
 */
package com.platform.whitebox.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.common.exception.BusinessException;
import com.platform.common.exception.ErrorCode;
import com.platform.execution.dto.ManualCaseCreateRequest;
import com.platform.execution.dto.ManualCaseResponse;
import com.platform.execution.service.ManualCaseService;
import com.platform.project.service.ProjectService;
import com.platform.repository.entity.CodeRepository;
import com.platform.repository.mapper.CodeRepositoryMapper;
import com.platform.requirement.entity.RequirementVersion;
import com.platform.requirement.mapper.RequirementVersionMapper;
import com.platform.whitebox.dto.WhiteboxMethodResponse;
import com.platform.whitebox.dto.WhiteboxReportResponse;
import com.platform.whitebox.dto.WhiteboxSaveTestsRequest;
import com.platform.whitebox.dto.WhiteboxStartRequest;
import com.platform.whitebox.dto.WhiteboxTaskResponse;
import com.platform.whitebox.dto.WhiteboxTestResponse;
import com.platform.whitebox.engine.WhiteboxTestEngine;
import com.platform.whitebox.entity.AiWhiteboxMethod;
import com.platform.whitebox.entity.AiWhiteboxReport;
import com.platform.whitebox.entity.AiWhiteboxTask;
import com.platform.whitebox.entity.AiWhiteboxTest;
import com.platform.whitebox.mapper.AiWhiteboxMethodMapper;
import com.platform.whitebox.mapper.AiWhiteboxReportMapper;
import com.platform.whitebox.mapper.AiWhiteboxTaskMapper;
import com.platform.whitebox.mapper.AiWhiteboxTestMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

/**
 * AI 白盒测试业务服务
 *
 * <p>负责白盒测试任务的启动/停止、结果查询、用例保存到手动用例库与报告下载。</p>
 */
@Slf4j
@Service
public class AiWhiteboxService {

    private final AiWhiteboxTaskMapper taskMapper;
    private final AiWhiteboxMethodMapper methodMapper;
    private final AiWhiteboxTestMapper testMapper;
    private final AiWhiteboxReportMapper reportMapper;
    private final CodeRepositoryMapper repositoryMapper;
    private final RequirementVersionMapper requirementVersionMapper;
    private final WhiteboxTestEngine testEngine;
    private final ThreadPoolExecutor whiteboxExecutor;
    private final ProjectService projectService;
    private final ManualCaseService manualCaseService;

    public AiWhiteboxService(AiWhiteboxTaskMapper taskMapper,
                             AiWhiteboxMethodMapper methodMapper,
                             AiWhiteboxTestMapper testMapper,
                             AiWhiteboxReportMapper reportMapper,
                             CodeRepositoryMapper repositoryMapper,
                             RequirementVersionMapper requirementVersionMapper,
                             WhiteboxTestEngine testEngine,
                             @Qualifier("whiteboxExecutor") ThreadPoolExecutor whiteboxExecutor,
                             ProjectService projectService,
                             ManualCaseService manualCaseService) {
        this.taskMapper = taskMapper;
        this.methodMapper = methodMapper;
        this.testMapper = testMapper;
        this.reportMapper = reportMapper;
        this.repositoryMapper = repositoryMapper;
        this.requirementVersionMapper = requirementVersionMapper;
        this.testEngine = testEngine;
        this.whiteboxExecutor = whiteboxExecutor;
        this.projectService = projectService;
        this.manualCaseService = manualCaseService;
    }

    /**
     * 启动白盒测试任务
     *
     * <p>基准 commit 优先级：请求指定 &gt; 该仓库最近一次 COMPLETED 任务的 head_commit；
     * 两者皆无则抛 WHITEBOX_NO_BASELINE（首次测试须手填）。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public WhiteboxTaskResponse startTask(Long projectId, WhiteboxStartRequest request) {
        projectService.findActiveById(projectId);

        // 校验无运行中任务
        LambdaQueryWrapper<AiWhiteboxTask> runningWrapper = new LambdaQueryWrapper<>();
        runningWrapper.eq(AiWhiteboxTask::getProjectId, projectId)
                .in(AiWhiteboxTask::getStatus, "PENDING", "RUNNING");
        Long runningCount = taskMapper.selectCount(runningWrapper);
        if (runningCount != null && runningCount > 0) {
            throw new BusinessException(ErrorCode.WHITEBOX_ALREADY_RUNNING, "该项目已有正在运行的白盒测试任务");
        }

        // 校验仓库归属
        CodeRepository repository = repositoryMapper.selectById(request.getRepositoryId());
        if (repository == null || !repository.getProjectId().equals(projectId)) {
            throw new BusinessException(ErrorCode.REPOSITORY_NOT_FOUND, "源代码仓库不存在：" + request.getRepositoryId());
        }

        // 校验需求版本（可选）
        String requirementVersionName = null;
        if (request.getRequirementVersionId() != null) {
            RequirementVersion version = requirementVersionMapper.selectById(request.getRequirementVersionId());
            if (version == null || !version.getProjectId().equals(projectId)) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "需求版本不存在");
            }
            requirementVersionName = version.getVersionName();
        }

        // 确定基准 commit
        String baselineCommit = null;
        if (StringUtils.hasText(request.getBaselineCommit())) {
            baselineCommit = request.getBaselineCommit().trim();
        } else {
            LambdaQueryWrapper<AiWhiteboxTask> lastWrapper = new LambdaQueryWrapper<>();
            lastWrapper.eq(AiWhiteboxTask::getProjectId, projectId)
                    .eq(AiWhiteboxTask::getRepositoryId, request.getRepositoryId())
                    .eq(AiWhiteboxTask::getStatus, "COMPLETED")
                    .isNotNull(AiWhiteboxTask::getHeadCommit)
                    .orderByDesc(AiWhiteboxTask::getId)
                    .last("LIMIT 1");
            AiWhiteboxTask lastTask = taskMapper.selectOne(lastWrapper);
            if (lastTask != null) {
                baselineCommit = lastTask.getHeadCommit();
            }
        }
        if (!StringUtils.hasText(baselineCommit)) {
            throw new BusinessException(ErrorCode.WHITEBOX_NO_BASELINE,
                    "未提供基准 commit 且无历史完成任务，首次测试请在请求中指定 baselineCommit");
        }

        AiWhiteboxTask task = new AiWhiteboxTask();
        task.setProjectId(projectId);
        task.setRepositoryId(repository.getId());
        task.setRepositoryName(repository.getName());
        task.setRequirementVersionId(request.getRequirementVersionId());
        task.setRequirementVersionName(requirementVersionName);
        task.setBaselineCommit(baselineCommit);
        task.setStatus("PENDING");
        task.setProgress(0);
        task.setTotalChangedFiles(0);
        task.setTotalChangedMethods(0);
        task.setTotalCases(0);
        task.setTotalTestClasses(0);
        task.setCompilePass(0);
        task.setExecPass(0);
        task.setExecFail(0);
        task.setTotalMutants(0);
        task.setKilledMutants(0);
        task.setTokensUsed(0L);
        task.setCreatedBy(getCurrentUserId());
        taskMapper.insert(task);

        // 异步执行任务
        try {
            CompletableFuture.runAsync(() -> testEngine.execute(task), whiteboxExecutor);
        } catch (Exception e) {
            log.error("提交白盒任务到线程池失败", e);
            task.setStatus("FAILED");
            task.setTaskLog("线程池队列已满，请稍后重试");
            task.setCompletedAt(LocalDateTime.now());
            taskMapper.updateById(task);
        }

        log.info("启动白盒测试任务: taskId={}, projectId={}, baseline={}",
                task.getId(), projectId, baselineCommit);
        return toTaskResponse(task);
    }

    /**
     * 停止白盒测试任务
     */
    @Transactional(rollbackFor = Exception.class)
    public void stopTask(Long projectId, Long taskId) {
        AiWhiteboxTask task = getTaskOrThrow(taskId, projectId);
        if (!"RUNNING".equals(task.getStatus()) && !"PENDING".equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.WHITEBOX_NOT_RUNNING, "白盒测试任务当前未在运行");
        }
        testEngine.cancel(taskId);
        log.info("已请求取消白盒测试任务: taskId={}", taskId);
    }

    /**
     * 查询项目下的白盒测试任务列表
     */
    public List<WhiteboxTaskResponse> listTasks(Long projectId) {
        LambdaQueryWrapper<AiWhiteboxTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiWhiteboxTask::getProjectId, projectId)
                .orderByDesc(AiWhiteboxTask::getCreatedAt);
        return taskMapper.selectList(wrapper).stream()
                .map(this::toTaskResponse)
                .collect(Collectors.toList());
    }

    /**
     * 查询任务详情（含进度与日志）
     */
    public WhiteboxTaskResponse getTask(Long projectId, Long taskId) {
        AiWhiteboxTask task = getTaskOrThrow(taskId, projectId);
        return toTaskResponse(task);
    }

    /**
     * 查询任务的变更方法列表
     */
    public List<WhiteboxMethodResponse> listMethods(Long projectId, Long taskId) {
        getTaskOrThrow(taskId, projectId);
        LambdaQueryWrapper<AiWhiteboxMethod> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiWhiteboxMethod::getTaskId, taskId)
                .orderByAsc(AiWhiteboxMethod::getId);
        return methodMapper.selectList(wrapper).stream()
                .map(this::toMethodResponse)
                .collect(Collectors.toList());
    }

    /**
     * 查询任务的生成测试列表
     */
    public List<WhiteboxTestResponse> listTests(Long projectId, Long taskId) {
        getTaskOrThrow(taskId, projectId);
        LambdaQueryWrapper<AiWhiteboxTest> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiWhiteboxTest::getTaskId, taskId)
                .orderByAsc(AiWhiteboxTest::getId);
        return testMapper.selectList(wrapper).stream()
                .map(this::toTestResponse)
                .collect(Collectors.toList());
    }

    /**
     * 保存生成用例到手动用例库
     *
     * <p>逐条校验未保存（saved_to_manual=0），组装 {@link ManualCaseCreateRequest}
     * 调用手动用例服务创建，回填 saved_to_manual=1 与 manual_case_id。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public List<Long> saveTests(Long projectId, Long taskId, WhiteboxSaveTestsRequest request) {
        AiWhiteboxTask task = getTaskOrThrow(taskId, projectId);

        List<Long> savedCaseIds = new java.util.ArrayList<>();
        for (Long testId : request.getTestIds()) {
            AiWhiteboxTest test = testMapper.selectById(testId);
            if (test == null || !test.getTaskId().equals(taskId)) {
                throw new BusinessException(ErrorCode.WHITEBOX_TASK_NOT_FOUND, "生成测试不存在：" + testId);
            }
            if (Integer.valueOf(1).equals(test.getSavedToManual())) {
                continue; // 已保存，跳过
            }

            ManualCaseCreateRequest createRequest = new ManualCaseCreateRequest();
            createRequest.setTitle(test.getCaseTitle());
            createRequest.setPreconditions(test.getPreconditions());
            createRequest.setOperationSteps(test.getOperationSteps());
            createRequest.setExpectedResult(test.getExpectedResult());
            createRequest.setCaseType(test.getCaseType());
            createRequest.setPriority(test.getPriority());
            ManualCaseResponse created = manualCaseService.createCase(projectId, createRequest);

            test.setSavedToManual(1);
            test.setManualCaseId(created.getId());
            testMapper.updateById(test);
            savedCaseIds.add(created.getId());
        }

        log.info("白盒任务 {} 保存 {} 个用例到手动用例库", taskId, savedCaseIds.size());
        return savedCaseIds;
    }

    /**
     * 获取任务报告（默认 Markdown 格式）
     */
    public WhiteboxReportResponse getReport(Long projectId, Long taskId) {
        getTaskOrThrow(taskId, projectId);
        LambdaQueryWrapper<AiWhiteboxReport> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiWhiteboxReport::getTaskId, taskId)
                .orderByAsc(AiWhiteboxReport::getId);
        List<AiWhiteboxReport> reports = reportMapper.selectList(wrapper);
        if (reports.isEmpty()) {
            throw new BusinessException(ErrorCode.WHITEBOX_TASK_NOT_FOUND, "测试报告不存在，请等待任务完成");
        }
        AiWhiteboxReport report = reports.stream()
                .filter(r -> "markdown".equals(r.getReportFormat()))
                .findFirst()
                .orElse(reports.get(0));
        return toReportResponse(report);
    }

    /**
     * 获取报告原始内容（用于下载）
     *
     * @param format 报告格式（markdown/json）
     */
    public AiWhiteboxReport getReportForDownload(Long projectId, Long taskId, String format) {
        getTaskOrThrow(taskId, projectId);
        LambdaQueryWrapper<AiWhiteboxReport> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiWhiteboxReport::getTaskId, taskId);
        if (StringUtils.hasText(format)) {
            wrapper.eq(AiWhiteboxReport::getReportFormat, format);
        }
        AiWhiteboxReport report = reportMapper.selectOne(wrapper);
        if (report == null) {
            throw new BusinessException(ErrorCode.WHITEBOX_TASK_NOT_FOUND, "指定格式的报告不存在");
        }
        return report;
    }

    // ===== 内部方法 =====

    private AiWhiteboxTask getTaskOrThrow(Long taskId, Long projectId) {
        AiWhiteboxTask task = taskMapper.selectById(taskId);
        if (task == null || !task.getProjectId().equals(projectId)) {
            throw new BusinessException(ErrorCode.WHITEBOX_TASK_NOT_FOUND, "白盒测试任务不存在");
        }
        return task;
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof com.platform.auth.entity.User) {
            return ((com.platform.auth.entity.User) auth.getPrincipal()).getId();
        }
        return null;
    }

    private WhiteboxTaskResponse toTaskResponse(AiWhiteboxTask task) {
        WhiteboxTaskResponse resp = new WhiteboxTaskResponse();
        resp.setId(task.getId());
        resp.setProjectId(task.getProjectId());
        resp.setRepositoryId(task.getRepositoryId());
        resp.setRepositoryName(task.getRepositoryName());
        resp.setRequirementVersionId(task.getRequirementVersionId());
        resp.setRequirementVersionName(task.getRequirementVersionName());
        resp.setBaselineCommit(task.getBaselineCommit());
        resp.setHeadCommit(task.getHeadCommit());
        resp.setStatus(task.getStatus());
        resp.setCurrentPhase(task.getCurrentPhase());
        resp.setProgress(task.getProgress());
        resp.setTaskLog(task.getTaskLog());
        resp.setTotalChangedFiles(task.getTotalChangedFiles());
        resp.setTotalChangedMethods(task.getTotalChangedMethods());
        resp.setTotalCases(task.getTotalCases());
        resp.setTotalTestClasses(task.getTotalTestClasses());
        resp.setCompilePass(task.getCompilePass());
        resp.setExecPass(task.getExecPass());
        resp.setExecFail(task.getExecFail());
        resp.setTotalMutants(task.getTotalMutants());
        resp.setKilledMutants(task.getKilledMutants());
        resp.setBranchCoverage(task.getBranchCoverage());
        resp.setLineCoverage(task.getLineCoverage());
        resp.setMutationScore(task.getMutationScore());
        resp.setTokensUsed(task.getTokensUsed());
        resp.setStartedAt(task.getStartedAt());
        resp.setCompletedAt(task.getCompletedAt());
        resp.setCreatedBy(task.getCreatedBy());
        resp.setCreatedAt(task.getCreatedAt());
        resp.setUpdatedAt(task.getUpdatedAt());
        return resp;
    }

    private WhiteboxMethodResponse toMethodResponse(AiWhiteboxMethod method) {
        WhiteboxMethodResponse resp = new WhiteboxMethodResponse();
        resp.setId(method.getId());
        resp.setTaskId(method.getTaskId());
        resp.setFilePath(method.getFilePath());
        resp.setClassName(method.getClassName());
        resp.setMethodName(method.getMethodName());
        resp.setMethodSignature(method.getMethodSignature());
        resp.setChangeType(method.getChangeType());
        resp.setStartLine(method.getStartLine());
        resp.setEndLine(method.getEndLine());
        resp.setSourceCode(method.getSourceCode());
        resp.setContextJson(method.getContextJson());
        resp.setRelatedRequirementsJson(method.getRelatedRequirementsJson());
        resp.setCreatedAt(method.getCreatedAt());
        return resp;
    }

    private WhiteboxTestResponse toTestResponse(AiWhiteboxTest test) {
        WhiteboxTestResponse resp = new WhiteboxTestResponse();
        resp.setId(test.getId());
        resp.setTaskId(test.getTaskId());
        resp.setMethodId(test.getMethodId());
        resp.setTestClassName(test.getTestClassName());
        resp.setTestMethodName(test.getTestMethodName());
        resp.setCaseTitle(test.getCaseTitle());
        resp.setCaseType(test.getCaseType());
        resp.setPriority(test.getPriority());
        resp.setPreconditions(test.getPreconditions());
        resp.setOperationSteps(test.getOperationSteps());
        resp.setExpectedResult(test.getExpectedResult());
        resp.setRelatedRequirementIds(test.getRelatedRequirementIds());
        resp.setTestCode(test.getTestCode());
        resp.setCompileStatus(test.getCompileStatus());
        resp.setExecStatus(test.getExecStatus());
        resp.setExecMessage(test.getExecMessage());
        resp.setSavedToManual(test.getSavedToManual());
        resp.setManualCaseId(test.getManualCaseId());
        resp.setCreatedAt(test.getCreatedAt());
        return resp;
    }

    private WhiteboxReportResponse toReportResponse(AiWhiteboxReport report) {
        WhiteboxReportResponse resp = new WhiteboxReportResponse();
        resp.setId(report.getId());
        resp.setTaskId(report.getTaskId());
        resp.setReportFormat(report.getReportFormat());
        resp.setReportContent(report.getReportContent());
        resp.setFileName(report.getFileName());
        resp.setFileSize(report.getFileSize());
        resp.setCreatedAt(report.getCreatedAt());
        return resp;
    }
}
