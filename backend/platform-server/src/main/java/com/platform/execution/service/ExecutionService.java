/**
 * @author HXN
 * @date 2026-08-20 15:34
 * @description 执行管理服务
 */
package com.platform.execution.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.auth.entity.User;
import com.platform.common.exception.BusinessException;
import com.platform.common.exception.ErrorCode;
import com.platform.common.response.PageResponse;
import com.platform.execution.dto.ExecutionResponse;
import com.platform.execution.dto.ExecutionStartRequest;
import com.platform.execution.dto.ManualCaseResultUpdateRequest;
import com.platform.execution.dto.ManualExecutionResponse;
import com.platform.execution.dto.ManualExecutionRowResponse;
import com.platform.execution.dto.ResultColumnResponse;
import com.platform.execution.dto.RoundCell;
import com.platform.execution.dto.RoundResultUpdateRequest;
import com.platform.execution.dto.TestResultResponse;
import com.platform.execution.entity.AutoCase;
import com.platform.execution.entity.ManualCase;
import com.platform.execution.entity.PlanResultColumn;
import com.platform.execution.entity.TestExecution;
import com.platform.execution.entity.TestPlan;
import com.platform.execution.entity.TestPlanManualCase;
import com.platform.execution.entity.TestResult;
import com.platform.execution.mapper.AutoCaseMapper;
import com.platform.execution.mapper.ManualCaseMapper;
import com.platform.execution.mapper.PlanResultColumnMapper;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private final PlanResultColumnMapper planResultColumnMapper;
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
        // 先查项目下的 planIds（并区分手动计划，用于过滤进行中的执行工作台单）
        LambdaQueryWrapper<TestPlan> planWrapper = new LambdaQueryWrapper<>();
        planWrapper.eq(TestPlan::getProjectId, projectId)
                .select(TestPlan::getId, TestPlan::getPlanType);
        if (StringUtils.hasText(planName)) {
            planWrapper.like(TestPlan::getName, planName);
        }
        List<TestPlan> plans = testPlanMapper.selectList(planWrapper);
        List<Long> planIds = plans.stream().map(TestPlan::getId).collect(Collectors.toList());
        List<Long> manualPlanIds = plans.stream()
                .filter(p -> "MANUAL".equals(p.getPlanType()))
                .map(TestPlan::getId)
                .collect(Collectors.toList());

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
        // 手动计划进行中的执行单（执行页工作台）归属“执行”而非“测试记录”，列表不展示
        if (!manualPlanIds.isEmpty()) {
            wrapper.not(w -> w.in(TestExecution::getPlanId, manualPlanIds)
                    .eq(TestExecution::getStatus, "WAITING_MANUAL"));
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
     *
     * <p>手动计划不走自动执行链路：直接创建“测试结果记录单”，由测试人员逐条记录用例是否通过。
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

        // 手动计划：不占并发槽、不发 MQ，直接生成待记录状态的测试结果记录单
        if ("MANUAL".equals(plan.getPlanType())) {
            return createManualExecutionRecord(execution);
        }

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
     * 创建手动计划的“测试结果记录单”（执行单）。
     *
     * <p>手动计划没有可自动执行的内容，执行单创建即进入 WAITING_MANUAL 状态，
     * 每条关联手动化用例预创建一条 PENDING 结果行，由测试人员进入执行页
     * （执行工作台）按自定义结果列记录多轮结果，点击【执行完成】归档为测试记录。
     *
     * <p>不走 MQ 与并发控制（无自动执行过程），不设置 startedAt（无“开始执行”时刻），
     * 计划上若误挂了自动化套件则忽略，仅记录手动化用例。
     */
    private ExecutionResponse createManualExecutionRecord(TestExecution execution) {
        // 读取计划关联的手动化用例（权威读源，按计划内顺序）
        List<Long> manualCaseIds = listActiveManualCaseIds(execution.getPlanId());
        if (manualCaseIds.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR,
                    "请先为计划添加测试用例，再执行手动测试计划");
        }

        execution.setTotalCases(manualCaseIds.size());
        execution.setStatus("WAITING_MANUAL");
        testExecutionMapper.insert(execution);

        // 预创建 PENDING 结果行，等待测试人员记录多轮结果
        for (Long manualCaseId : manualCaseIds) {
            insertPendingResult(execution.getId(), manualCaseId);
        }

        log.info("创建手动测试执行单: planId={}, executionId={}, manualCases={}",
                execution.getPlanId(), execution.getId(), manualCaseIds.size());
        return toResponse(execution);
    }

    // ===== 手动计划执行工作台（执行页） =====

    /**
     * 获取或创建手动计划的进行中执行单（执行页加载入口）
     *
     * <p>存在进行中的执行单则复用，并补齐执行期间计划新增用例的结果行；
     * 否则新建执行单并预创建结果行（“点击执行仅跳转”，创建发生在执行页加载时）。
     */
    @Transactional(rollbackFor = Exception.class)
    public ManualExecutionResponse getCurrentManualExecution(Long planId) {
        TestPlan plan = testPlanMapper.selectById(planId);
        if (plan == null) {
            throw new BusinessException(ErrorCode.PLAN_NOT_FOUND, "测试计划不存在：" + planId);
        }
        if (!"MANUAL".equals(plan.getPlanType())) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "仅手动测试计划可使用执行页：" + planId);
        }

        List<Long> manualCaseIds = listActiveManualCaseIds(planId);
        if (manualCaseIds.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR,
                    "请先为计划添加测试用例，再执行手动测试计划");
        }

        LambdaQueryWrapper<TestExecution> execWrapper = new LambdaQueryWrapper<>();
        execWrapper.eq(TestExecution::getPlanId, planId)
                .eq(TestExecution::getStatus, "WAITING_MANUAL")
                .orderByDesc(TestExecution::getCreatedAt)
                .orderByDesc(TestExecution::getId)
                .last("LIMIT 1");
        TestExecution execution = testExecutionMapper.selectOne(execWrapper);

        if (execution == null) {
            execution = new TestExecution();
            execution.setPlanId(planId);
            execution.setEnvironmentId(plan.getEnvironmentId());
            execution.setTriggerType("MANUAL");
            execution.setTotalCases(manualCaseIds.size());
            execution.setPassedCases(0);
            execution.setFailedCases(0);
            execution.setSkippedCases(0);
            execution.setTriggeredBy(getCurrentUserId());
            execution.setCreatedAt(LocalDateTime.now());
            execution.setStatus("WAITING_MANUAL");
            testExecutionMapper.insert(execution);

            for (Long manualCaseId : manualCaseIds) {
                insertPendingResult(execution.getId(), manualCaseId);
            }
            log.info("创建手动计划执行单: planId={}, executionId={}, manualCases={}",
                    planId, execution.getId(), manualCaseIds.size());
        } else {
            // 补齐执行期间计划新增用例的结果行（已移除用例的历史行保留不删）
            syncManualExecutionRows(execution, manualCaseIds);
        }

        return toManualExecutionResponse(plan, execution);
    }

    /**
     * 补齐执行单中缺失的用例结果行（执行期间计划新增用例时调用）
     */
    private void syncManualExecutionRows(TestExecution execution, List<Long> manualCaseIds) {
        LambdaQueryWrapper<TestResult> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TestResult::getExecutionId, execution.getId())
                .eq(TestResult::getCaseType, "MANUAL");
        List<TestResult> existing = testResultMapper.selectList(wrapper);
        Set<Long> existingCaseIds = existing.stream()
                .map(TestResult::getManualCaseId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        int added = 0;
        for (Long manualCaseId : manualCaseIds) {
            if (!existingCaseIds.contains(manualCaseId)) {
                insertPendingResult(execution.getId(), manualCaseId);
                added++;
            }
        }
        if (added > 0) {
            execution.setTotalCases(existing.size() + added);
            testExecutionMapper.updateById(execution);
            log.info("补齐执行单用例行: executionId={}, added={}", execution.getId(), added);
        }
    }

    /**
     * 更新执行工作台单元格（用例行 × 结果列的多轮结果）
     *
     * <p>覆盖式更新该格 status 与 remark（两者均空视为清除该格）；
     * 仅进行中的执行单（WAITING_MANUAL）可编辑。
     */
    @Transactional(rollbackFor = Exception.class)
    public TestResultResponse updateRoundResult(Long executionId, RoundResultUpdateRequest request) {
        TestExecution execution = testExecutionMapper.selectById(executionId);
        if (execution == null) {
            throw new BusinessException(ErrorCode.EXECUTION_NOT_FOUND, "执行记录不存在：" + executionId);
        }
        if (!"WAITING_MANUAL".equals(execution.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "执行单已完成，不可再修改多轮结果");
        }

        TestResult result = testResultMapper.selectById(request.getResultId());
        if (result == null || !Objects.equals(result.getExecutionId(), executionId)
                || !"MANUAL".equalsIgnoreCase(result.getCaseType())) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "测试结果与执行记录不匹配");
        }

        PlanResultColumn column = planResultColumnMapper.selectById(request.getColumnId());
        if (column == null || !Objects.equals(column.getPlanId(), execution.getPlanId())) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "结果列与执行单不属于同一计划");
        }

        String status = StringUtils.hasText(request.getStatus()) ? request.getStatus().toUpperCase() : null;
        if (status != null && !"PASSED".equals(status) && !"FAILED".equals(status) && !"SKIPPED".equals(status)) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "无效的结果状态：" + request.getStatus());
        }
        String remark = StringUtils.hasText(request.getRemark()) ? request.getRemark() : null;

        Map<String, RoundCell> roundResults = parseRoundResults(result.getRoundResults());
        String columnKey = String.valueOf(column.getId());
        if (status == null && remark == null) {
            roundResults.remove(columnKey);
        } else {
            RoundCell cell = new RoundCell();
            cell.setStatus(status);
            cell.setRemark(remark);
            roundResults.put(columnKey, cell);
        }

        try {
            result.setRoundResults(objectMapper.writeValueAsString(roundResults));
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "序列化多轮结果失败");
        }
        testResultMapper.updateById(result);
        return toResultResponse(result);
    }

    /**
     * 完成手动计划执行（执行页【执行完成】）
     *
     * <p>快照当前计划级结果列定义到执行单后置为 COMPLETED，
     * 即形成一条【测试记录】，此后出现在测试记录列表并在详情页只读回放。
     */
    @Transactional(rollbackFor = Exception.class)
    public ExecutionResponse completeManualExecution(Long executionId) {
        TestExecution execution = testExecutionMapper.selectById(executionId);
        if (execution == null) {
            throw new BusinessException(ErrorCode.EXECUTION_NOT_FOUND, "执行记录不存在：" + executionId);
        }
        if (!"WAITING_MANUAL".equals(execution.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "仅进行中的执行单可执行完成操作");
        }

        // 快照计划级结果列定义（列后续改名/删除不影响归档记录展示）
        LambdaQueryWrapper<PlanResultColumn> columnWrapper = new LambdaQueryWrapper<>();
        columnWrapper.eq(PlanResultColumn::getPlanId, execution.getPlanId())
                .orderByAsc(PlanResultColumn::getSortNo)
                .orderByAsc(PlanResultColumn::getId);
        List<Map<String, Object>> snapshot = new ArrayList<>();
        for (PlanResultColumn column : planResultColumnMapper.selectList(columnWrapper)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", column.getId());
            item.put("columnName", column.getColumnName());
            item.put("sortNo", column.getSortNo());
            snapshot.add(item);
        }
        try {
            execution.setResultColumns(objectMapper.writeValueAsString(snapshot));
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "序列化结果列快照失败");
        }

        execution.setStatus("COMPLETED");
        execution.setFinishedAt(LocalDateTime.now());
        testExecutionMapper.updateById(execution);

        log.info("完成手动计划执行单: planId={}, executionId={}, columns={}",
                execution.getPlanId(), executionId, snapshot.size());
        return toResponse(execution);
    }

    /**
     * 组装执行工作台响应（执行单信息 + 计划级结果列 + 用例行含多轮结果）
     */
    private ManualExecutionResponse toManualExecutionResponse(TestPlan plan, TestExecution execution) {
        ManualExecutionResponse resp = new ManualExecutionResponse();
        resp.setExecutionId(execution.getId());
        resp.setPlanId(plan.getId());
        resp.setPlanName(plan.getName());
        resp.setStatus(execution.getStatus());
        resp.setTotalCases(execution.getTotalCases());
        resp.setCreatedAt(execution.getCreatedAt());

        // 计划级结果列定义（历次执行共享，按 sortNo 升序）
        LambdaQueryWrapper<PlanResultColumn> columnWrapper = new LambdaQueryWrapper<>();
        columnWrapper.eq(PlanResultColumn::getPlanId, plan.getId())
                .orderByAsc(PlanResultColumn::getSortNo)
                .orderByAsc(PlanResultColumn::getId);
        List<ResultColumnResponse> columns = new ArrayList<>();
        for (PlanResultColumn column : planResultColumnMapper.selectList(columnWrapper)) {
            ResultColumnResponse columnResp = new ResultColumnResponse();
            BeanUtils.copyProperties(column, columnResp);
            columns.add(columnResp);
        }
        resp.setColumns(columns);

        // 用例行（按执行单内结果行 ID 顺序，即计划内用例顺序）
        LambdaQueryWrapper<TestResult> rowWrapper = new LambdaQueryWrapper<>();
        rowWrapper.eq(TestResult::getExecutionId, execution.getId())
                .eq(TestResult::getCaseType, "MANUAL")
                .orderByAsc(TestResult::getId);
        List<ManualExecutionRowResponse> rows = new ArrayList<>();
        for (TestResult result : testResultMapper.selectList(rowWrapper)) {
            rows.add(toManualExecutionRow(result));
        }
        resp.setRows(rows);
        return resp;
    }

    /**
     * 组装执行工作台用例行（用例信息 + 多轮结果）
     */
    private ManualExecutionRowResponse toManualExecutionRow(TestResult result) {
        ManualExecutionRowResponse row = new ManualExecutionRowResponse();
        row.setResultId(result.getId());
        row.setManualCaseId(result.getManualCaseId());
        row.setRoundResults(parseRoundResults(result.getRoundResults()));

        ManualCase manualCase = result.getManualCaseId() != null
                ? manualCaseMapper.selectById(result.getManualCaseId()) : null;
        if (manualCase != null) {
            row.setTitle(manualCase.getTitle());
            row.setCaseType(manualCase.getCaseType());
            row.setPriority(manualCase.getPriority());
            row.setCaseStatus(manualCase.getCaseStatus());
        }
        return row;
    }

    /**
     * 解析多轮结果 JSON（空值/解析失败返回空 Map）
     */
    private Map<String, RoundCell> parseRoundResults(String json) {
        if (!StringUtils.hasText(json)) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, RoundCell>>() {});
        } catch (Exception e) {
            log.warn("解析多轮测试结果失败: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * 读取计划关联的手动化用例 ID（权威读源，按计划内顺序，剔除已不存在的用例）
     */
    private List<Long> listActiveManualCaseIds(Long planId) {
        LambdaQueryWrapper<TestPlanManualCase> relWrapper = new LambdaQueryWrapper<>();
        relWrapper.eq(TestPlanManualCase::getPlanId, planId)
                .orderByAsc(TestPlanManualCase::getSortNo)
                .orderByAsc(TestPlanManualCase::getId);
        List<Long> manualCaseIds = new ArrayList<>();
        for (TestPlanManualCase relation : testPlanManualCaseMapper.selectList(relWrapper)) {
            if (manualCaseMapper.selectById(relation.getManualCaseId()) != null) {
                manualCaseIds.add(relation.getManualCaseId());
            }
        }
        return manualCaseIds;
    }

    /**
     * 预创建一条 PENDING 结果行（执行单用例行的载体）
     */
    private void insertPendingResult(Long executionId, Long manualCaseId) {
        TestResult testResult = new TestResult();
        testResult.setExecutionId(executionId);
        testResult.setManualCaseId(manualCaseId);
        testResult.setCaseType("MANUAL");
        testResult.setStatus("PENDING");
        testResult.setStartedAt(LocalDateTime.now());
        testResultMapper.insert(testResult);
    }

    /**
     * 取消执行
     *
     * <p>如果取消的是 RUNNING 任务，触发下一个排队任务。
     *
     * <p>WAITING_MANUAL 状态的取消语义为作废：手动计划的测试结果记录单不再继续记录，
     * 已标记的结果保留、未标记的保持 PENDING，记录冻结为 CANCELLED。
     */
    @Transactional(rollbackFor = Exception.class)
    public ExecutionResponse cancelExecution(Long executionId) {
        TestExecution execution = testExecutionMapper.selectById(executionId);
        if (execution == null) {
            throw new BusinessException(ErrorCode.EXECUTION_NOT_FOUND, "执行记录不存在：" + executionId);
        }

        if (!"PENDING".equals(execution.getStatus())
                && !"RUNNING".equals(execution.getStatus())
                && !"QUEUED".equals(execution.getStatus())
                && !"WAITING_MANUAL".equals(execution.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR,
                    "只能取消 PENDING、RUNNING、QUEUED 或 WAITING_MANUAL 状态的执行");
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

        // 获取计划名称与类型
        TestPlan plan = testPlanMapper.selectById(execution.getPlanId());
        if (plan != null) {
            resp.setPlanName(plan.getName());
            resp.setPlanType(plan.getPlanType());
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

        // 解析结果列快照（仅手动计划执行完成时写入，供测试记录详情页只读回放）
        if (StringUtils.hasText(execution.getResultColumns())) {
            try {
                resp.setResultColumns(objectMapper.readValue(execution.getResultColumns(),
                        new TypeReference<List<Map<String, Object>>>() {}));
            } catch (Exception e) {
                log.warn("解析执行结果列快照失败: executionId={}, error={}", execution.getId(), e.getMessage());
            }
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

        // 解析多轮测试结果单元格值（手动计划执行页填写的数据）
        resp.setRoundResults(parseRoundResults(result.getRoundResults()));

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
