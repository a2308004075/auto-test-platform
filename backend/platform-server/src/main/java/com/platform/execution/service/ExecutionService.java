/**
 * @author HXN
 * @date 2026-08-20 15:34
 * @description 执行管理服务
 */
package com.platform.execution.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.auth.entity.User;
import com.platform.common.exception.BusinessException;
import com.platform.common.exception.ErrorCode;
import com.platform.common.response.PageResponse;
import com.platform.execution.dto.ExecutionResponse;
import com.platform.execution.dto.ExecutionStartRequest;
import com.platform.execution.dto.ManualCaseResultUpdateRequest;
import com.platform.execution.dto.TestResultResponse;
import com.platform.execution.entity.AutoCase;
import com.platform.execution.entity.ManualCase;
import com.platform.execution.entity.TestExecution;
import com.platform.execution.entity.TestPlan;
import com.platform.execution.entity.TestPlanManualCase;
import com.platform.execution.entity.TestResult;
import com.platform.execution.mapper.AutoCaseMapper;
import com.platform.execution.mapper.ManualCaseMapper;
import com.platform.execution.mapper.TestExecutionMapper;
import com.platform.execution.mapper.TestPlanManualCaseMapper;
import com.platform.execution.mapper.TestPlanMapper;
import com.platform.execution.mapper.TestResultMapper;
import com.platform.execution.mq.ExecutionMessage;
import com.platform.execution.mq.ExecutionProducer;
import com.platform.environment.entity.Environment;
import com.platform.environment.mapper.EnvironmentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 测试执行服务
 *
 * <p>负责执行记录的查询、触发执行和取消。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionService {

    @Value("${execution.max-concurrent:3}")
    private int maxConcurrent;

    private final TestExecutionMapper testExecutionMapper;
    private final TestPlanMapper testPlanMapper;
    private final TestResultMapper testResultMapper;
    private final AutoCaseMapper autoCaseMapper;
    private final ManualCaseMapper manualCaseMapper;
    private final TestPlanManualCaseMapper testPlanManualCaseMapper;
    private final EnvironmentMapper environmentMapper;
    private final ExecutionProducer executionProducer;
    private final ObjectMapper objectMapper;

    /**
     * 分页查询项目下的执行记录（支持多条件过滤）
     */
    public PageResponse<ExecutionResponse> listExecutions(Long projectId, String planName,
                                                          Long environmentId, String status,
                                                          String triggerType, String startedAtFrom,
                                                          String startedAtTo, String finishedAtFrom,
                                                          String finishedAtTo, int page, int pageSize) {
        // 先查项目下的 planIds
        LambdaQueryWrapper<TestPlan> planWrapper = new LambdaQueryWrapper<>();
        planWrapper.eq(TestPlan::getProjectId, projectId)
                .select(TestPlan::getId);
        if (StringUtils.hasText(planName)) {
            planWrapper.like(TestPlan::getName, planName);
        }
        List<TestPlan> plans = testPlanMapper.selectList(planWrapper);
        List<Long> planIds = plans.stream().map(TestPlan::getId).collect(Collectors.toList());

        if (planIds.isEmpty()) {
            return PageResponse.empty((long) page, (long) pageSize);
        }

        LambdaQueryWrapper<TestExecution> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(TestExecution::getPlanId, planIds);
        if (environmentId != null) {
            wrapper.eq(TestExecution::getEnvironmentId, environmentId);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(TestExecution::getStatus, status);
        }
        if (StringUtils.hasText(triggerType)) {
            wrapper.eq(TestExecution::getTriggerType, triggerType);
        }
        if (StringUtils.hasText(startedAtFrom)) {
            wrapper.ge(TestExecution::getStartedAt, LocalDate.parse(startedAtFrom).atStartOfDay());
        }
        if (StringUtils.hasText(startedAtTo)) {
            wrapper.le(TestExecution::getStartedAt, LocalDate.parse(startedAtTo).atTime(LocalTime.MAX));
        }
        if (StringUtils.hasText(finishedAtFrom)) {
            wrapper.ge(TestExecution::getFinishedAt, LocalDate.parse(finishedAtFrom).atStartOfDay());
        }
        if (StringUtils.hasText(finishedAtTo)) {
            wrapper.le(TestExecution::getFinishedAt, LocalDate.parse(finishedAtTo).atTime(LocalTime.MAX));
        }
        wrapper.orderByDesc(TestExecution::getCreatedAt);

        Page<TestExecution> result = testExecutionMapper.selectPage(new Page<>(page, pageSize), wrapper);
        List<ExecutionResponse> records = new ArrayList<>(result.getRecords().size());
        for (TestExecution e : result.getRecords()) {
            records.add(toResponse(e));
        }
        return PageResponse.of(records, result.getTotal(), page, pageSize);
    }

    /**
     * 获取执行详情
     */
    public ExecutionResponse getExecution(Long executionId) {
        TestExecution execution = testExecutionMapper.selectById(executionId);
        if (execution == null) {
            throw new BusinessException(ErrorCode.EXECUTION_NOT_FOUND, "执行记录不存在：" + executionId);
        }
        return toResponse(execution);
    }

    /**
     * 获取执行结果明细
     */
    public List<TestResultResponse> getResults(Long executionId) {
        LambdaQueryWrapper<TestResult> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TestResult::getExecutionId, executionId)
                .orderByAsc(TestResult::getStartedAt);
        List<TestResult> results = testResultMapper.selectList(wrapper);

        List<TestResultResponse> records = new ArrayList<>(results.size());
        for (TestResult r : results) {
            records.add(toResultResponse(r));
        }
        return records;
    }

    /**
     * 触发执行
     *
     * <p>并发控制：当 RUNNING 状态的执行数已达上限时，
     * 新执行记录状态设为 QUEUED，等待前置任务完成后自动触发。
     */
    @Transactional(rollbackFor = Exception.class)
    public ExecutionResponse startExecution(Long planId, ExecutionStartRequest request) {
        TestPlan plan = testPlanMapper.selectById(planId);
        if (plan == null) {
            throw new BusinessException(ErrorCode.PLAN_NOT_FOUND, "测试计划不存在：" + planId);
        }

        // 创建执行记录
        TestExecution execution = new TestExecution();
        execution.setPlanId(planId);
        execution.setEnvironmentId(request.getEnvironmentId() != null
                ? request.getEnvironmentId() : plan.getEnvironmentId());
        execution.setTriggerType(request.getTriggerType() != null
                ? request.getTriggerType() : "MANUAL");
        execution.setTotalCases(countPlannedCases(plan));
        execution.setPassedCases(0);
        execution.setFailedCases(0);
        execution.setSkippedCases(0);
        execution.setTriggeredBy(getCurrentUserId());
        execution.setCreatedAt(LocalDateTime.now());

        // 并发控制：检查当前 RUNNING 数量
        int runningCount = countRunningExecutions();
        if (runningCount >= maxConcurrent) {
            execution.setStatus("QUEUED");
            testExecutionMapper.insert(execution);
            log.info("并发上限已达，执行排队: planId={}, executionId={}, running={}/{}",
                    planId, execution.getId(), runningCount, maxConcurrent);
            return toResponse(execution);
        }

        execution.setStatus("PENDING");
        testExecutionMapper.insert(execution);

        // 发送 MQ 消息异步执行
        sendExecutionMessage(execution, planId);

        log.info("触发执行: planId={}, executionId={}", planId, execution.getId());
        return toResponse(execution);
    }

    /**
     * 取消执行
     *
     * <p>如果取消的是 RUNNING 任务，触发下一个排队任务。
     */
    @Transactional(rollbackFor = Exception.class)
    public ExecutionResponse cancelExecution(Long executionId) {
        TestExecution execution = testExecutionMapper.selectById(executionId);
        if (execution == null) {
            throw new BusinessException(ErrorCode.EXECUTION_NOT_FOUND, "执行记录不存在：" + executionId);
        }

        if (!"PENDING".equals(execution.getStatus())
                && !"RUNNING".equals(execution.getStatus())
                && !"QUEUED".equals(execution.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR,
                    "只能取消 PENDING、RUNNING 或 QUEUED 状态的执行");
        }

        boolean wasRunning = "RUNNING".equals(execution.getStatus());

        execution.setStatus("CANCELLED");
        execution.setFinishedAt(LocalDateTime.now());
        testExecutionMapper.updateById(execution);

        // 如果取消的是 RUNNING 任务，触发下一个排队任务
        if (wasRunning) {
            triggerNextQueued();
        }

        return toResponse(execution);
    }

    /**
     * 查询当前 RUNNING 状态的执行数量
     */
    public int countRunningExecutions() {
        LambdaQueryWrapper<TestExecution> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TestExecution::getStatus, "RUNNING");
        return Math.toIntExact(testExecutionMapper.selectCount(wrapper));
    }

    /**
     * 触发下一个排队中的执行任务
     *
     * <p>查找最早的 QUEUED 状态记录，更新为 PENDING 并发送 MQ 消息。
     */
    @Transactional(rollbackFor = Exception.class)
    public void triggerNextQueued() {
        LambdaQueryWrapper<TestExecution> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TestExecution::getStatus, "QUEUED")
                .orderByAsc(TestExecution::getCreatedAt)
                .last("LIMIT 1");
        TestExecution queued = testExecutionMapper.selectOne(wrapper);
        if (queued == null) {
            return;
        }

        // 再次检查并发数（防止竞态）
        if (countRunningExecutions() >= maxConcurrent) {
            log.debug("并发数仍达上限，跳过触发排队任务");
            return;
        }

        queued.setStatus("PENDING");
        testExecutionMapper.updateById(queued);

        sendExecutionMessage(queued, queued.getPlanId());
        log.info("触发排队任务: executionId={}, planId={}", queued.getId(), queued.getPlanId());
    }

    /**
     * 计算计划实际会执行的自动化与手动化用例总数。
     * 手动化用例改从计划-用例关联表读取（权威读源，test_plan.manual_case_ids JSON 列仅作写镜像）。
     */
    private int countPlannedCases(TestPlan plan) {
        int total = 0;
        for (Long autoSuiteId : parseIdList(plan.getAutoSuiteIds())) {
            LambdaQueryWrapper<AutoCase> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(AutoCase::getAutoSuiteId, autoSuiteId)
                    .eq(AutoCase::getIsActive, true);
            total += autoCaseMapper.selectCount(wrapper);
        }
        LambdaQueryWrapper<TestPlanManualCase> relWrapper = new LambdaQueryWrapper<>();
        relWrapper.eq(TestPlanManualCase::getPlanId, plan.getId());
        for (TestPlanManualCase relation : testPlanManualCaseMapper.selectList(relWrapper)) {
            if (manualCaseMapper.selectById(relation.getManualCaseId()) != null) {
                total++;
            }
        }
        return total;
    }

    private List<Long> parseIdList(String idListJson) {
        if (!StringUtils.hasText(idListJson)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(idListJson, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            log.warn("解析计划关联用例 ID 列表失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 发送执行消息到 MQ
     */
    private void sendExecutionMessage(TestExecution execution, Long planId) {
        ExecutionMessage message = new ExecutionMessage();
        message.setExecutionId(execution.getId());
        message.setPlanId(planId);
        message.setEnvironmentId(execution.getEnvironmentId());
        message.setTriggeredBy(execution.getTriggeredBy());
        message.setTriggerType(execution.getTriggerType());
        executionProducer.sendExecutionMessage(message);
    }

    private ExecutionResponse toResponse(TestExecution execution) {
        ExecutionResponse resp = new ExecutionResponse();
        BeanUtils.copyProperties(execution, resp);

        // 获取计划名称
        TestPlan plan = testPlanMapper.selectById(execution.getPlanId());
        if (plan != null) {
            resp.setPlanName(plan.getName());
        }

        // 获取环境名称
        if (execution.getEnvironmentId() != null) {
            Environment env = environmentMapper.selectById(execution.getEnvironmentId());
            if (env != null) {
                resp.setEnvironmentName(env.getName());
            }
        }

        // 计算通过率和进度百分比
        int total = execution.getTotalCases() != null ? execution.getTotalCases() : 0;
        int passed = execution.getPassedCases() != null ? execution.getPassedCases() : 0;
        int failed = execution.getFailedCases() != null ? execution.getFailedCases() : 0;
        int skipped = execution.getSkippedCases() != null ? execution.getSkippedCases() : 0;

        if (total > 0) {
            resp.setPassRate(Math.round(passed * 1000.0 / total) / 10.0);
        }
        int completed = passed + failed + skipped;
        if (total > 0) {
            resp.setProgressPercent((int) Math.round(completed * 100.0 / total));
        }

        return resp;
    }

    /**
     * 更新手动化用例执行结果
     *
     * <p>仅允许更新状态为 PENDING 的手动化用例结果，更新后同步调整执行记录统计。
     * 当该执行记录下所有手动化用例均已标记时，自动将执行状态置为 COMPLETED。
     */
    @Transactional(rollbackFor = Exception.class)
    public TestResultResponse updateManualCaseResult(Long executionId, ManualCaseResultUpdateRequest request) {
        TestResult result = testResultMapper.selectById(request.getResultId());
        if (result == null) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "测试结果不存在");
        }
        if (!Objects.equals(result.getExecutionId(), executionId)) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "结果与执行记录不匹配");
        }
        if (!"MANUAL".equalsIgnoreCase(result.getCaseType())) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "仅支持更新手动化用例结果");
        }
        if (!"PENDING".equalsIgnoreCase(result.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "手动化用例结果已标记，不可重复更新");
        }

        String status = request.getStatus().toUpperCase();
        if (!"PASSED".equals(status) && !"FAILED".equals(status) && !"SKIPPED".equals(status)) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "无效的执行结果状态");
        }

        result.setStatus(status);
        result.setActualResult(request.getActualResult());
        result.setErrorMessage(request.getErrorMessage());
        result.setFinishedAt(LocalDateTime.now());
        testResultMapper.updateById(result);

        // 总用例数在触发执行时已按自动化与手动化用例总数预先计算，此处只更新结果统计。
        TestExecution execution = testExecutionMapper.selectById(executionId);
        if (execution != null) {
            switch (status) {
                case "PASSED":
                    execution.setPassedCases((execution.getPassedCases() != null ? execution.getPassedCases() : 0) + 1);
                    break;
                case "FAILED":
                case "ERROR":
                    execution.setFailedCases((execution.getFailedCases() != null ? execution.getFailedCases() : 0) + 1);
                    break;
                default:
                    execution.setSkippedCases((execution.getSkippedCases() != null ? execution.getSkippedCases() : 0) + 1);
            }

            // 自动化部分结束并进入 WAITING_MANUAL 后，所有手动化用例均标记完成才结束测试记录。
            if ("WAITING_MANUAL".equals(execution.getStatus()) && isAllManualCasesMarked(executionId)) {
                execution.setStatus("COMPLETED");
                execution.setFinishedAt(LocalDateTime.now());
                if (execution.getStartedAt() != null) {
                    execution.setDurationMs((int) (System.currentTimeMillis() - execution.getStartedAt()
                            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()));
                }
            }
            testExecutionMapper.updateById(execution);
        }

        return toResultResponse(result);
    }

    /**
     * 判断指定执行记录下所有手动化用例是否均已标记
     */
    private boolean isAllManualCasesMarked(Long executionId) {
        LambdaQueryWrapper<TestResult> totalWrapper = new LambdaQueryWrapper<>();
        totalWrapper.eq(TestResult::getExecutionId, executionId)
                .eq(TestResult::getCaseType, "MANUAL");
        long total = testResultMapper.selectCount(totalWrapper);
        if (total == 0) {
            return true;
        }

        LambdaQueryWrapper<TestResult> pendingWrapper = new LambdaQueryWrapper<>();
        pendingWrapper.eq(TestResult::getExecutionId, executionId)
                .eq(TestResult::getCaseType, "MANUAL")
                .eq(TestResult::getStatus, "PENDING");
        long pending = testResultMapper.selectCount(pendingWrapper);
        return pending == 0;
    }

    private TestResultResponse toResultResponse(TestResult result) {
        TestResultResponse resp = new TestResultResponse();
        BeanUtils.copyProperties(result, resp);

        // 获取用例名称
        if ("MANUAL".equalsIgnoreCase(result.getCaseType())) {
            ManualCase manualCase = manualCaseMapper.selectById(result.getManualCaseId());
            if (manualCase != null) {
                resp.setCaseName(manualCase.getTitle());
            }
        } else {
            AutoCase autoCase = autoCaseMapper.selectById(result.getAutoCaseId());
            if (autoCase != null) {
                resp.setCaseName(autoCase.getName());
            }
        }

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
