/**
 * @author HXN
 * @date 2026-08-20 15:34
 * @description 测试计划执行器
 */
package com.platform.execution.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.execution.entity.AutoCase;
import com.platform.execution.entity.AutoSuite;
import com.platform.execution.entity.ManualCase;
import com.platform.execution.entity.TestExecution;
import com.platform.execution.entity.TestPlan;
import com.platform.execution.entity.TestPlanManualCase;
import com.platform.execution.entity.TestResult;
import com.platform.execution.mapper.AutoCaseMapper;
import com.platform.execution.mapper.AutoSuiteMapper;
import com.platform.execution.mapper.ManualCaseMapper;
import com.platform.execution.mapper.TestExecutionMapper;
import com.platform.execution.mapper.TestPlanManualCaseMapper;
import com.platform.execution.mapper.TestPlanMapper;
import com.platform.execution.mapper.TestResultMapper;
import com.platform.execution.websocket.ExecutionWebSocketHandler;
import com.platform.environment.service.EnvironmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 测试计划执行器
 *
 * <p>顶层执行器，协调整个执行流程：
 * <ol>
 *   <li>加载 TestExecution + TestPlan</li>
 *   <li>创建 ExecutionContext（加载环境配置）</li>
 *   <li>遍历自动化套件 → 遍历自动化用例 → 执行</li>
 *   <li>保存 TestResult + 更新 TestExecution 统计</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlanExecutor {

    private final AutoSuiteExecutor autoSuiteExecutor;
    private final TestPlanMapper testPlanMapper;
    private final TestExecutionMapper testExecutionMapper;
    private final TestResultMapper testResultMapper;
    private final AutoSuiteMapper autoSuiteMapper;
    private final AutoCaseMapper autoCaseMapper;
    private final ManualCaseMapper manualCaseMapper;
    private final TestPlanManualCaseMapper testPlanManualCaseMapper;
    private final EnvironmentService environmentService;
    private final ObjectMapper objectMapper;
    private final ExecutionWebSocketHandler executionWebSocketHandler;

    /**
     * 执行测试计划
     *
     * <p>执行流程：
     * <ol>
     *   <li>更新状态为 RUNNING</li>
     *   <li>预计算总自动化用例数（用于进度百分比）</li>
     *   <li>遍历自动化套件，推送 suite_start / per-case / suite_end 事件</li>
     *   <li>汇总统计并推送 plan_end 事件</li>
     * </ol>
     *
     * @param executionId 执行记录 ID
     */
    public void execute(Long executionId) {
        TestExecution execution = testExecutionMapper.selectById(executionId);
        if (execution == null) {
            log.error("执行记录不存在：{}", executionId);
            return;
        }

        // 更新为 RUNNING
        execution.setStatus("RUNNING");
        execution.setStartedAt(LocalDateTime.now());
        testExecutionMapper.updateById(execution);
        sendProgress(executionId, "RUNNING", 0, 0, 0, 0, 0, 0, 0.0, null, "开始执行");

        long startMs = System.currentTimeMillis();
        int totalCases = 0;
        int passedCases = 0;
        int failedCases = 0;
        int skippedCases = 0;
        boolean hasError = false;
        int manualCaseTotal = 0;

        try {
            // 查测试计划
            TestPlan plan = testPlanMapper.selectById(execution.getPlanId());
            if (plan == null) {
                throw new RuntimeException("测试计划不存在：" + execution.getPlanId());
            }

            // 创建执行上下文
            ExecutionContext context = buildContext(execution, plan);
            log.info("开始执行计划: plan={}, execution={}, env={}", plan.getName(), executionId, context.getEnvironmentId());

            // 解析 autoSuiteIds（JSON 列），manualCaseIds 改从关联表读取（权威读源，按计划内顺序）
            List<Long> autoSuiteIds = parseIdList(plan.getAutoSuiteIds());
            List<Long> manualCaseIds = listManualCaseIds(plan.getId());
            if (autoSuiteIds.isEmpty() && manualCaseIds.isEmpty()) {
                log.warn("计划未关联任何自动化套件或手动化用例: {}", plan.getName());
            }

            // 预计算自动化用例数量，并为手动化用例预创建 PENDING 结果记录。
            // 仅统计实际存在的手动化用例，保证总数与可更新的结果记录一致。
            int expectedAutoTotal = countExpectedAutoCases(autoSuiteIds);
            for (Long manualCaseId : manualCaseIds) {
                ManualCase manualCase = manualCaseMapper.selectById(manualCaseId);
                if (manualCase == null) {
                    log.warn("手动化用例不存在，跳过: {}", manualCaseId);
                    continue;
                }
                TestResult testResult = new TestResult();
                testResult.setExecutionId(executionId);
                testResult.setManualCaseId(manualCaseId);
                testResult.setCaseType("MANUAL");
                testResult.setStatus("PENDING");
                testResult.setStartedAt(LocalDateTime.now());
                testResultMapper.insert(testResult);
                manualCaseTotal++;
            }
            int expectedTotal = expectedAutoTotal + manualCaseTotal;

            // 遍历自动化套件执行
            for (Long autoSuiteId : autoSuiteIds) {
                AutoSuite suite = autoSuiteMapper.selectById(autoSuiteId);
                if (suite == null) {
                    log.warn("自动化套件不存在，跳过: {}", autoSuiteId);
                    continue;
                }

                // 推送套件开始事件
                sendProgress(executionId, "RUNNING", totalCases, passedCases, failedCases, skippedCases,
                        (int) (System.currentTimeMillis() - startMs),
                        calcPercent(totalCases, expectedTotal),
                        calcPassRate(passedCases, totalCases),
                        null, "开始执行自动化套件「" + suite.getName() + "」");

                AutoSuiteExecutor.AutoSuiteExecutionResult suiteResult = autoSuiteExecutor.execute(suite, context);

                // 处理套件结果，逐条保存并推送进度
                for (AutoSuiteExecutor.AutoCaseExecutionSummary caseSummary : suiteResult.getCaseResults()) {
                    // 保存 TestResult
                    TestResult testResult = new TestResult();
                    testResult.setExecutionId(executionId);
                    testResult.setAutoCaseId(caseSummary.getAutoCaseId());
                    testResult.setCaseType("AUTO");
                    testResult.setStatus(caseSummary.getStatus());
                    testResult.setActualResult(caseSummary.getMessage());
                    testResult.setDurationMs((int) caseSummary.getDurationMs());
                    testResult.setStartedAt(LocalDateTime.now());
                    testResult.setFinishedAt(LocalDateTime.now());
                    try {
                        testResult.setLogs(objectMapper.writeValueAsString(caseSummary.getStepLogs()));
                    } catch (Exception e) {
                        log.warn("序列化步骤日志失败: {}", e.getMessage());
                    }
                    testResultMapper.insert(testResult);

                    // 累计统计
                    totalCases++;
                    switch (caseSummary.getStatus()) {
                        case "PASSED":
                            passedCases++;
                            break;
                        case "FAILED":
                        case "ERROR":
                            failedCases++;
                            break;
                        default:
                            skippedCases++;
                    }

                    // 推送每条自动化用例完成事件
                    sendProgress(executionId, "RUNNING", totalCases, passedCases, failedCases, skippedCases,
                            (int) (System.currentTimeMillis() - startMs),
                            calcPercent(totalCases, expectedTotal),
                            calcPassRate(passedCases, totalCases),
                            caseSummary.getCaseName(),
                            "自动化用例「" + caseSummary.getCaseName() + "」" + caseSummary.getStatus());
                }

                // 推送套件完成事件
                sendProgress(executionId, "RUNNING", totalCases, passedCases, failedCases, skippedCases,
                        (int) (System.currentTimeMillis() - startMs),
                        calcPercent(totalCases, expectedTotal),
                        calcPassRate(passedCases, totalCases),
                        null, "自动化套件「" + suite.getName() + "」执行完成");
            }

        } catch (Throwable e) {
            // 刻意捕获 Throwable：Error 场景也要将执行记录置为 FAILED 并推送完成事件，
            // 保证执行状态闭环，避免前端进度条永久停留在 RUNNING
            log.error("执行计划异常: execution={}", executionId, e);
            hasError = true;
        }

        // 汇总已生成的自动化和手动化结果，避免人工标记与自动化执行并行时覆盖统计值。
        long elapsed = System.currentTimeMillis() - startMs;
        TestResultStatistics statistics = collectResultStatistics(executionId);
        int completedCases = statistics.passedCases + statistics.failedCases + statistics.skippedCases;
        execution.setTotalCases(statistics.totalCases);
        execution.setPassedCases(statistics.passedCases);
        execution.setFailedCases(statistics.failedCases);
        execution.setSkippedCases(statistics.skippedCases);
        execution.setDurationMs((int) elapsed);

        String finalStatus;
        String finalMessage;
        if (hasError) {
            finalStatus = "FAILED";
            finalMessage = "执行失败";
            execution.setFinishedAt(LocalDateTime.now());
        } else if (statistics.pendingManualCases > 0) {
            finalStatus = "WAITING_MANUAL";
            finalMessage = "自动化部分执行完成，等待手动化用例标记结果";
            execution.setFinishedAt(null);
        } else {
            finalStatus = "COMPLETED";
            finalMessage = "执行完成";
            execution.setFinishedAt(LocalDateTime.now());
        }
        execution.setStatus(finalStatus);
        testExecutionMapper.updateById(execution);

        // 手动化用例仍待处理时，进度按已完成结果计算；其他终态保持 100%。
        int progressPercent = "WAITING_MANUAL".equals(finalStatus)
                ? calcPercent(completedCases, statistics.totalCases) : 100;
        sendProgress(executionId, finalStatus, statistics.totalCases, statistics.passedCases, statistics.failedCases,
                statistics.skippedCases, (int) elapsed, progressPercent,
                calcPassRate(statistics.passedCases, statistics.totalCases), null, finalMessage);
        log.info("执行完成: execution={}, total={}, passed={}, failed={}, skipped={}, duration={}ms",
                executionId, statistics.totalCases, statistics.passedCases, statistics.failedCases,
                statistics.skippedCases, elapsed);
    }

    /**
     * 预计算所有自动化套件下启用的自动化用例总数
     */
    private int countExpectedAutoCases(List<Long> autoSuiteIds) {
        int total = 0;
        for (Long autoSuiteId : autoSuiteIds) {
            LambdaQueryWrapper<AutoCase> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(AutoCase::getAutoSuiteId, autoSuiteId)
                    .eq(AutoCase::getIsActive, true);
            total += autoCaseMapper.selectCount(wrapper);
        }
        return total;
    }

    /**
     * 从计划-用例关联表读取手动化用例 ID 列表（权威读源，test_plan.manual_case_ids JSON 列仅作写镜像）
     */
    private List<Long> listManualCaseIds(Long planId) {
        LambdaQueryWrapper<TestPlanManualCase> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TestPlanManualCase::getPlanId, planId)
                .orderByAsc(TestPlanManualCase::getSortNo)
                .orderByAsc(TestPlanManualCase::getId);
        return testPlanManualCaseMapper.selectList(wrapper).stream()
                .map(TestPlanManualCase::getManualCaseId)
                .collect(Collectors.toList());
    }

    /**
     * 汇总当前执行记录下的测试结果。
     */
    private TestResultStatistics collectResultStatistics(Long executionId) {
        LambdaQueryWrapper<TestResult> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TestResult::getExecutionId, executionId);

        TestResultStatistics statistics = new TestResultStatistics();
        for (TestResult result : testResultMapper.selectList(wrapper)) {
            statistics.totalCases++;
            if ("PENDING".equals(result.getStatus()) && "MANUAL".equalsIgnoreCase(result.getCaseType())) {
                statistics.pendingManualCases++;
                continue;
            }
            if ("PASSED".equals(result.getStatus())) {
                statistics.passedCases++;
            } else if ("FAILED".equals(result.getStatus()) || "ERROR".equals(result.getStatus())) {
                statistics.failedCases++;
            } else if (!"PENDING".equals(result.getStatus())) {
                statistics.skippedCases++;
            }
        }
        return statistics;
    }

    private static class TestResultStatistics {
        private int totalCases;
        private int passedCases;
        private int failedCases;
        private int skippedCases;
        private int pendingManualCases;
    }

    /**
     * 计算进度百分比
     */
    private int calcPercent(int processed, int total) {
        if (total <= 0) return 0;
        return Math.min(100, (int) ((long) processed * 100 / total));
    }

    /**
     * 计算通过率
     */
    private double calcPassRate(int passed, int total) {
        if (total <= 0) return 0.0;
        return Math.round(passed * 1000.0 / total) / 10.0;
    }

    /**
     * 构建执行上下文
     */
    private ExecutionContext buildContext(TestExecution execution, TestPlan plan) {
        ExecutionContext context = new ExecutionContext();
        context.setExecutionId(execution.getId());
        context.setProjectId(plan.getProjectId());
        context.setEnvironmentId(execution.getEnvironmentId());

        // 加载环境变量
        Long envId = execution.getEnvironmentId() != null
                ? execution.getEnvironmentId() : plan.getEnvironmentId();
        if (envId != null) {
            context.setEnvironmentId(envId);
            try {
                Map<String, String> variables = environmentService.getVariablesAsMap(envId);

                // 从变量中构建 baseUrl（查找 host 变量）
                String host = variables.get("host");
                if (host != null && !host.isEmpty()) {
                    context.setBaseUrl(host);
                }

                // 将所有环境变量加载到执行上下文中，支持 ${var} 引用
                for (Map.Entry<String, String> entry : variables.entrySet()) {
                    context.setVariable(entry.getKey(), entry.getValue());
                }
            } catch (Exception e) {
                log.warn("加载环境变量失败: {}", e.getMessage());
            }
        }

        return context;
    }

    /**
     * 解析 ID 列表 JSON
     */
    private List<Long> parseIdList(String idListJson) {
        if (idListJson == null || idListJson.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(idListJson, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            log.warn("解析 ID 列表失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 通过 WebSocket 推送执行进度
     *
     * @param executionId      执行记录 ID
     * @param status           执行状态
     * @param totalCases       总自动化用例数
     * @param passedCases      通过数
     * @param failedCases      失败数
     * @param skippedCases     跳过数
     * @param durationMs       耗时（毫秒）
     * @param progressPercent  进度百分比（0-100）
     * @param passRate         通过率（0-100，保留1位小数）
     * @param currentCaseName  当前自动化用例名称
     * @param message          消息
     */
    private void sendProgress(Long executionId, String status, int totalCases,
                              int passedCases, int failedCases, int skippedCases,
                              int durationMs, int progressPercent, double passRate,
                              String currentCaseName, String message) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "PROGRESS");
            payload.put("status", status);
            payload.put("totalCases", totalCases);
            payload.put("passedCases", passedCases);
            payload.put("failedCases", failedCases);
            payload.put("skippedCases", skippedCases);
            payload.put("durationMs", durationMs);
            payload.put("progressPercent", progressPercent);
            payload.put("passRate", passRate);
            if (currentCaseName != null) {
                payload.put("currentCaseName", currentCaseName);
            }
            payload.put("message", message);
            executionWebSocketHandler.sendProgress(String.valueOf(executionId), objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("推送执行进度失败: {}", e.getMessage());
        }
    }
}
