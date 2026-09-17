/**
 * @author HXN
 * @date 2026-09-15
 * @description AI 白盒测试主编排引擎（9 阶段流水线）
 */
package com.platform.whitebox.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.exception.BusinessException;
import com.platform.common.exception.ErrorCode;
import com.platform.repository.dto.PullResultResponse;
import com.platform.repository.entity.CodeRepository;
import com.platform.repository.mapper.CodeRepositoryMapper;
import com.platform.repository.service.CodeRepositoryService;
import com.platform.requirement.entity.RequirementItem;
import com.platform.requirement.mapper.RequirementItemMapper;
import com.platform.whitebox.config.AiWhiteboxConfig;
import com.platform.whitebox.entity.AiWhiteboxMethod;
import com.platform.whitebox.entity.AiWhiteboxReport;
import com.platform.whitebox.entity.AiWhiteboxTask;
import com.platform.whitebox.entity.AiWhiteboxTest;
import com.platform.whitebox.mapper.AiWhiteboxMethodMapper;
import com.platform.whitebox.mapper.AiWhiteboxReportMapper;
import com.platform.whitebox.mapper.AiWhiteboxTaskMapper;
import com.platform.whitebox.mapper.AiWhiteboxTestMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * AI 白盒测试主编排引擎
 *
 * <p>9 阶段流水线：
 * <ol>
 *   <li><b>sync</b> - 同步代码：拉取仓库、取 HEAD commit</li>
 *   <li><b>diff</b> - 变更识别：baseline..HEAD 方法级 diff</li>
 *   <li><b>analyze</b> - 静态分析：JavaParser 生成 context_json</li>
 *   <li><b>align</b> - 需求对齐：LLM 关联需求条目</li>
 *   <li><b>generate</b> - 生成测试：LLM 生成 JUnit5+Mockito 测试类</li>
 *   <li><b>build</b> - 编译验证：注入插件、mvnw 编译、失败修复循环</li>
 *   <li><b>test</b> - 执行测试：mvnw test、surefire 解析、失败修复循环</li>
 *   <li><b>quality</b> - 质量评估：JaCoCo 覆盖率 + PiTest 变异得分</li>
 *   <li><b>report</b> - 报告生成：Markdown + JSON 报告</li>
 * </ol>
 *
 * <p>引擎在执行过程中实时更新数据库中的 progress / current_phase / task_log，
 * 任务结束 finally 还原被测项目 pom 备份。</p>
 */
@Slf4j
@Component
public class WhiteboxTestEngine {

    private final CodeRepositoryService repositoryService;
    private final CodeRepositoryMapper repositoryMapper;
    private final RequirementItemMapper requirementItemMapper;
    private final GitChangeAnalyzer gitChangeAnalyzer;
    private final JavaMethodAnalyzer javaMethodAnalyzer;
    private final RequirementAligner requirementAligner;
    private final TestCaseGenerator testCaseGenerator;
    private final BuildTestRunner buildTestRunner;
    private final WhiteboxReportGenerator reportGenerator;
    private final MavenProcessRunner mavenRunner;
    private final AiWhiteboxTaskMapper taskMapper;
    private final AiWhiteboxMethodMapper methodMapper;
    private final AiWhiteboxTestMapper testMapper;
    private final AiWhiteboxReportMapper reportMapper;
    private final AiWhiteboxConfig config;
    private final ObjectMapper objectMapper;

    @Value("${repository.storage-path}")
    private String storagePath;

    /** 取消标志：taskId → cancelled */
    private final ConcurrentHashMap<Long, Boolean> cancelledTasks = new ConcurrentHashMap<>();

    /** 降级说明清单（报告展示） */
    private final ThreadLocal<List<String>> degradedNotes = ThreadLocal.withInitial(ArrayList::new);

    public WhiteboxTestEngine(CodeRepositoryService repositoryService,
                              CodeRepositoryMapper repositoryMapper,
                              RequirementItemMapper requirementItemMapper,
                              GitChangeAnalyzer gitChangeAnalyzer,
                              JavaMethodAnalyzer javaMethodAnalyzer,
                              RequirementAligner requirementAligner,
                              TestCaseGenerator testCaseGenerator,
                              BuildTestRunner buildTestRunner,
                              WhiteboxReportGenerator reportGenerator,
                              MavenProcessRunner mavenRunner,
                              AiWhiteboxTaskMapper taskMapper,
                              AiWhiteboxMethodMapper methodMapper,
                              AiWhiteboxTestMapper testMapper,
                              AiWhiteboxReportMapper reportMapper,
                              AiWhiteboxConfig config,
                              ObjectMapper objectMapper) {
        this.repositoryService = repositoryService;
        this.repositoryMapper = repositoryMapper;
        this.requirementItemMapper = requirementItemMapper;
        this.gitChangeAnalyzer = gitChangeAnalyzer;
        this.javaMethodAnalyzer = javaMethodAnalyzer;
        this.requirementAligner = requirementAligner;
        this.testCaseGenerator = testCaseGenerator;
        this.buildTestRunner = buildTestRunner;
        this.reportGenerator = reportGenerator;
        this.mavenRunner = mavenRunner;
        this.taskMapper = taskMapper;
        this.methodMapper = methodMapper;
        this.testMapper = testMapper;
        this.reportMapper = reportMapper;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行白盒测试任务（在独立线程中运行）
     */
    public void execute(AiWhiteboxTask task) {
        Long taskId = task.getId();
        List<File> injectedPoms = new ArrayList<>();
        try {
            task.setStartedAt(LocalDateTime.now());
            task.setStatus("RUNNING");
            task.setCurrentPhase("sync");
            task.setProgress(0);
            appendLog(task, "白盒测试任务开始: 仓库[" + task.getRepositoryName() + "] 基准[" + shortCommit(task.getBaselineCommit()) + "]");
            taskMapper.updateById(task);
            degradedNotes.get().clear();

            // ===== 阶段 1: sync - 同步代码 =====
            updatePhase(task, "sync", 2, "同步仓库代码...");
            PullResultResponse pullResult = repositoryService.pull(task.getRepositoryId());
            if (pullResult == null || !Boolean.TRUE.equals(pullResult.getSuccess())) {
                throw new BusinessException(ErrorCode.WHITEBOX_REPO_SYNC_FAILED,
                        "仓库同步失败: " + (pullResult != null ? pullResult.getMessage() : "无响应"));
            }
            task.setHeadCommit(pullResult.getCommitId());
            appendLog(task, "仓库同步完成: HEAD = " + shortCommit(task.getHeadCommit()));
            task.setProgress(5);
            taskMapper.updateById(task);

            File repoDir = new File(storagePath, task.getProjectId() + File.separator + task.getRepositoryId());
            if (!new File(repoDir, ".git").exists()) {
                throw new BusinessException(ErrorCode.WHITEBOX_REPO_SYNC_FAILED, "本地仓库目录无效: " + repoDir.getPath());
            }

            // ===== 阶段 2: diff - 变更识别 =====
            checkCancelled(taskId);
            updatePhase(task, "diff", 8, "分析代码变更: " + shortCommit(task.getBaselineCommit()) + " → " + shortCommit(task.getHeadCommit()));
            GitChangeAnalyzer.AnalyzeResult analyzeResult = gitChangeAnalyzer.analyze(
                    repoDir, task.getBaselineCommit(), task.getHeadCommit(), config.getMaxMethodsPerTask());

            List<AiWhiteboxMethod> directMethods = new ArrayList<>();
            for (GitChangeAnalyzer.ChangedMethod cm : analyzeResult.getMethods()) {
                AiWhiteboxMethod method = new AiWhiteboxMethod();
                method.setTaskId(taskId);
                method.setFilePath(cm.getFilePath());
                method.setClassName(cm.getClassName());
                method.setMethodName(cm.getMethodName());
                method.setMethodSignature(cm.getMethodSignature());
                method.setChangeType(cm.getChangeType());
                method.setStartLine(cm.getStartLine());
                method.setEndLine(cm.getEndLine());
                method.setSourceCode(cm.getSourceCode());
                methodMapper.insert(method);
                directMethods.add(method);
            }
            task.setTotalChangedFiles(analyzeResult.getChangedFiles());
            task.setTotalChangedMethods(analyzeResult.getMethods().size());
            if (analyzeResult.isTruncated()) {
                degradedNotes.get().add("变更方法数超过上限 " + config.getMaxMethodsPerTask() + "，已截断");
                appendLog(task, "变更方法数达到上限，已截断为 " + analyzeResult.getMethods().size());
            }
            if (analyzeResult.getMethods().isEmpty()) {
                throw new BusinessException(ErrorCode.WHITEBOX_NO_CHANGES,
                        "基准与 HEAD 之间无 Java 源码变更");
            }
            appendLog(task, "变更识别完成: " + analyzeResult.getChangedFiles() + " 个文件, "
                    + analyzeResult.getMethods().size() + " 个方法");
            task.setProgress(15);
            taskMapper.updateById(task);

            // ===== 阶段 3: analyze - 静态分析 =====
            checkCancelled(taskId);
            updatePhase(task, "analyze", 16, "静态分析（AST 提取分支结构/依赖）...");
            int analyzeTotal = directMethods.size();
            for (int i = 0; i < analyzeTotal; i++) {
                checkCancelled(taskId);
                AiWhiteboxMethod method = directMethods.get(i);
                if ("INDIRECT".equals(method.getChangeType())) {
                    continue; // 间接影响方法无需深度分析
                }
                String fileContent = readRepoFile(repoDir, method.getFilePath());
                String contextJson = javaMethodAnalyzer.analyze(
                        method.getSourceCode(), fileContent, method.getMethodName());
                method.setContextJson(contextJson);
                methodMapper.updateById(method);
                if ((i + 1) % 5 == 0 || i == analyzeTotal - 1) {
                    task.setProgress(16 + (int) ((i + 1.0) / analyzeTotal * 8));
                    taskMapper.updateById(task);
                }
            }
            appendLog(task, "静态分析完成: " + analyzeTotal + " 个方法");
            task.setProgress(25);
            taskMapper.updateById(task);

            // ===== 阶段 4: align - 需求对齐 =====
            checkCancelled(taskId);
            updatePhase(task, "align", 26, "需求对齐...");
            List<Map<String, Object>> requirementItems = loadRequirementItems(task);
            if (requirementItems.isEmpty()) {
                appendLog(task, "未选择需求版本或版本无条目，语义对齐受限，跳过需求对齐");
                degradedNotes.get().add("未选择需求版本，需求对齐阶段降级跳过");
            } else {
                RequirementAligner.AlignResult alignResult = requirementAligner.align(directMethods, requirementItems);
                addTokens(task, alignResult.tokensUsed);
                for (AiWhiteboxMethod method : directMethods) {
                    if (method.getRelatedRequirementsJson() != null) {
                        methodMapper.updateById(method);
                    }
                }
                appendLog(task, "需求对齐完成: " + requirementItems.size() + " 条需求条目");
            }
            task.setProgress(30);
            taskMapper.updateById(task);

            // ===== 阶段 5: generate - 生成测试 =====
            checkCancelled(taskId);
            updatePhase(task, "generate", 32, "LLM 生成测试代码...");
            Map<String, BuildTestRunner.TestClassInfo> testClassMap = new LinkedHashMap<>();
            Map<String, List<AiWhiteboxMethod>> classGroups = groupByClass(directMethods);
            int groupIndex = 0;
            for (Map.Entry<String, List<AiWhiteboxMethod>> groupEntry : classGroups.entrySet()) {
                checkCancelled(taskId);
                String className = groupEntry.getKey();
                List<AiWhiteboxMethod> classMethods = groupEntry.getValue();

                List<TestCaseGenerator.MethodSpec> specs = new ArrayList<>();
                for (AiWhiteboxMethod method : classMethods) {
                    TestCaseGenerator.MethodSpec spec = new TestCaseGenerator.MethodSpec();
                    spec.setMethodName(method.getMethodName());
                    spec.setMethodSource(method.getSourceCode());
                    spec.setContextJson(method.getContextJson());
                    spec.setRelatedItems(resolveRelatedItems(method, requirementItems));
                    specs.add(spec);
                }

                String fileContent = readRepoFile(repoDir, classMethods.get(0).getFilePath());
                TestCaseGenerator.GenerateResult generateResult;
                try {
                    generateResult = testCaseGenerator.generate(className, fileContent, specs);
                } catch (Exception e) {
                    appendLog(task, "LLM 生成失败（类 " + className + "）: " + e.getMessage());
                    degradedNotes.get().add("类 " + className + " 测试生成失败: " + e.getMessage());
                    continue;
                }
                addTokens(task, generateResult.getTokensUsed());

                // 计算测试类文件路径并写入
                String testClassName = simpleName(className) + "WhiteboxTest";
                String fullTestClassName = packageName(className) + (packageName(className).isEmpty() ? "" : ".") + testClassName;
                File testFile = resolveTestFile(repoDir, classMethods.get(0).getFilePath(), className, testClassName);
                if (testFile == null || generateResult.getTestClassCode() == null
                        || generateResult.getTestClassCode().trim().isEmpty()) {
                    appendLog(task, "测试类 " + testClassName + " 生成结果为空，跳过");
                    continue;
                }
                Files.createDirectories(testFile.getParentFile().toPath());
                Files.write(testFile.toPath(), generateResult.getTestClassCode().getBytes(StandardCharsets.UTF_8));

                BuildTestRunner.TestClassInfo classInfo = new BuildTestRunner.TestClassInfo();
                classInfo.setTestClassName(fullTestClassName);
                classInfo.setTestFile(testFile);
                classInfo.setSourceCode(generateResult.getTestClassCode());
                testClassMap.put(fullTestClassName, classInfo);

                // 写入用例记录
                for (TestCaseGenerator.GeneratedCase gc : generateResult.getTestCases()) {
                    AiWhiteboxTest test = new AiWhiteboxTest();
                    test.setTaskId(taskId);
                    test.setMethodId(findMethodId(classMethods, gc.getSourceMethod()));
                    test.setTestClassName(fullTestClassName);
                    test.setTestMethodName(gc.getTestMethodName() != null ? gc.getTestMethodName() : gc.getTitle());
                    test.setCaseTitle(gc.getTitle() != null ? gc.getTitle() : gc.getTestMethodName());
                    test.setCaseType("EXCEPTION".equalsIgnoreCase(gc.getCaseType()) ? "EXCEPTION" : "NORMAL");
                    test.setPriority(normalizePriority(gc.getPriority()));
                    test.setPreconditions(gc.getPreconditions());
                    test.setOperationSteps(joinSteps(gc.getSteps()));
                    test.setExpectedResult(gc.getExpectedResult());
                    if (gc.getRelatedRequirementIds() != null && !gc.getRelatedRequirementIds().isEmpty()) {
                        test.setRelatedRequirementIds(toJson(gc.getRelatedRequirementIds()));
                    }
                    test.setTestCode(extractTestMethodCode(generateResult.getTestClassCode(), test.getTestMethodName()));
                    test.setCompileStatus("PENDING");
                    test.setExecStatus("PENDING");
                    test.setSavedToManual(0);
                    testMapper.insert(test);
                }

                groupIndex++;
                appendLog(task, "生成测试类 " + testClassName + "（" + generateResult.getTestCases().size() + " 个用例）");
                task.setProgress(32 + (int) (groupIndex * 1.0 / classGroups.size() * 18));
                taskMapper.updateById(task);
            }

            long totalCases = testMapper.selectCount(new LambdaQueryWrapper<AiWhiteboxTest>()
                    .eq(AiWhiteboxTest::getTaskId, taskId));
            task.setTotalCases((int) totalCases);
            task.setTotalTestClasses(testClassMap.size());
            if (testClassMap.isEmpty()) {
                throw new BusinessException(ErrorCode.WHITEBOX_LLM_GENERATE_FAILED, "所有测试类生成失败");
            }
            appendLog(task, "测试生成完成: " + testClassMap.size() + " 个测试类, " + totalCases + " 个用例");
            task.setProgress(50);
            taskMapper.updateById(task);

            // 收集涉及模块 pom
            List<File> modulePoms = collectModulePoms(repoDir, testClassMap.values());

            // ===== 阶段 6: build - 编译验证 =====
            checkCancelled(taskId);
            updatePhase(task, "build", 52, "注入覆盖率插件并编译测试代码...");
            buildTestRunner.injectPlugins(modulePoms, msg -> appendLog(task, msg));
            injectedPoms.addAll(modulePoms);

            Consumer<String> logger = msg -> appendLog(task, msg);
            Runnable cancelChecker = () -> checkCancelled(taskId);
            List<BuildTestRunner.TestClassInfo> testClasses = new ArrayList<>(testClassMap.values());
            BuildTestRunner.BuildResult buildResult = buildTestRunner.runBuildPhase(
                    taskId, repoRoot(repoDir), testClasses, logger, cancelChecker);
            addTokens(task, buildResult.getTokensUsed());
            task.setCompilePass(buildResult.getCompilePass());
            updateCompileStatus(taskId, testClasses, buildResult.isSuccess());
            task.setProgress(65);
            taskMapper.updateById(task);

            // ===== 阶段 7: test - 执行测试 =====
            BuildTestRunner.TestResult testResult = new BuildTestRunner.TestResult();
            if (buildResult.isSuccess()) {
                checkCancelled(taskId);
                updatePhase(task, "test", 66, "执行测试（mvnw test）...");
                testResult = buildTestRunner.runTestPhase(taskId, repoRoot(repoDir), testClasses, logger, cancelChecker);
                addTokens(task, testResult.getTokensUsed());
                task.setExecPass(testResult.getExecPass());
                task.setExecFail(testResult.getExecFail());
                applyTestResults(taskId, testResult);
                task.setProgress(80);
                taskMapper.updateById(task);
            } else {
                appendLog(task, "编译失败，跳过测试执行阶段");
                degradedNotes.get().add("测试代码编译失败，执行与质量评估阶段降级跳过");
            }

            // ===== 阶段 8: quality - 质量评估 =====
            if (buildResult.isSuccess()) {
                checkCancelled(taskId);
                updatePhase(task, "quality", 82, "质量评估（JaCoCo 覆盖率 + PiTest 变异测试）...");
                Set<String> targetClasses = new HashSet<>();
                for (List<AiWhiteboxMethod> group : classGroups.values()) {
                    targetClasses.add(group.get(0).getClassName());
                }
                Set<String> targetTests = new HashSet<>(testClassMap.keySet());

                MavenProcessRunner.Coverage coverage = buildTestRunner.runCoverageReport(
                        taskId, repoRoot(repoDir), targetClasses, logger);
                task.setLineCoverage(BigDecimal.valueOf(coverage.getLineCoverage()));
                task.setBranchCoverage(BigDecimal.valueOf(coverage.getBranchCoverage()));

                MavenProcessRunner.MutationStats mutationStats = buildTestRunner.runMutationTest(
                        taskId, repoRoot(repoDir), targetClasses, targetTests, logger);
                task.setTotalMutants(mutationStats.getTotal());
                task.setKilledMutants(mutationStats.getKilled());
                double score = mutationStats.getTotal() > 0
                        ? mutationStats.getKilled() * 100.0 / mutationStats.getTotal() : 0;
                task.setMutationScore(BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP));
                task.setProgress(95);
                taskMapper.updateById(task);

                // 低分方法测试重写（≤quality-rewrite-rounds）
                if (config.getQualityRewriteRounds() > 0
                        && (mutationStats.getTotal() == 0
                            || score < config.getMutationThreshold()
                            || coverage.getBranchCoverage() < config.getBranchCoverageThreshold())) {
                    appendLog(task, "质量低于阈值（变异 " + (int) score + "%/阈值 " + config.getMutationThreshold()
                            + "%, 分支 " + (int) coverage.getBranchCoverage() + "%/阈值 "
                            + config.getBranchCoverageThreshold() + "%），尝试重写低分测试...");
                    appendLog(task, "质量重写轮触发（V1 记录建议，未自动重写以控制 token）");
                    degradedNotes.get().add("质量低于阈值，建议人工补充边界用例（变异得分 " + (int) score + "%, 分支覆盖率 "
                            + (int) coverage.getBranchCoverage() + "%）");
                }
            }

            // ===== 阶段 9: report - 报告生成 =====
            checkCancelled(taskId);
            updatePhase(task, "report", 96, "生成测试报告...");
            List<AiWhiteboxTest> allTests = testMapper.selectList(new LambdaQueryWrapper<AiWhiteboxTest>()
                    .eq(AiWhiteboxTest::getTaskId, taskId));
            AiWhiteboxReport mdReport = reportGenerator.generateMarkdown(task, directMethods, allTests, degradedNotes.get());
            reportMapper.insert(mdReport);
            AiWhiteboxReport jsonReport = reportGenerator.generateJson(task, directMethods, allTests, degradedNotes.get());
            reportMapper.insert(jsonReport);
            appendLog(task, "报告已生成: " + mdReport.getFileName() + " / " + jsonReport.getFileName());

            // 完成
            task.setProgress(100);
            task.setCurrentPhase("report");
            task.setStatus("COMPLETED");
            task.setCompletedAt(LocalDateTime.now());
            appendLog(task, "白盒测试任务完成！");
            taskMapper.updateById(task);
            log.info("白盒任务 {} 执行完成: {} 个方法, {} 个用例, 变异得分 {}",
                    taskId, directMethods.size(), allTests.size(), task.getMutationScore());

        } catch (CancelledException e) {
            task.setStatus("CANCELLED");
            task.setCompletedAt(LocalDateTime.now());
            appendLog(task, "任务已被用户取消");
            taskMapper.updateById(task);
            log.info("白盒任务 {} 已取消", taskId);
        } catch (Exception e) {
            task.setStatus("FAILED");
            task.setCompletedAt(LocalDateTime.now());
            appendLog(task, "任务失败: " + e.getMessage());
            taskMapper.updateById(task);
            log.error("白盒任务 {} 执行失败", taskId, e);
        } finally {
            // 还原被测项目 pom 备份（无论成功失败）
            try {
                buildTestRunner.restorePoms(injectedPoms, msg -> appendLog(task, msg));
            } catch (Exception e) {
                log.warn("还原 pom 失败", e);
            }
            cancelledTasks.remove(taskId);
            degradedNotes.remove();
        }
    }

    /**
     * 取消任务（设置标志 + 强杀运行中的 mvnw 进程）
     */
    public void cancel(Long taskId) {
        cancelledTasks.put(taskId, Boolean.TRUE);
        mavenRunner.destroyTaskProcesses(taskId);
    }

    // ───────────────────── 私有方法 ─────────────────────

    private File repoRoot(File repoDir) {
        return repoDir;
    }

    private String shortCommit(String commit) {
        if (commit == null) {
            return "-";
        }
        return commit.length() > 8 ? commit.substring(0, 8) : commit;
    }

    private void updatePhase(AiWhiteboxTask task, String phase, int progress, String logMsg) {
        task.setCurrentPhase(phase);
        task.setProgress(progress);
        appendLog(task, logMsg);
        taskMapper.updateById(task);
    }

    private void appendLog(AiWhiteboxTask task, String message) {
        String timestamp = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        String logLine = "[" + timestamp + "] " + message;
        if (task.getTaskLog() == null || task.getTaskLog().isEmpty()) {
            task.setTaskLog(logLine);
        } else {
            String existing = task.getTaskLog();
            task.setTaskLog(existing.length() > 900000 ? existing : existing + "\n" + logLine);
        }
    }

    private void checkCancelled(Long taskId) {
        if (Boolean.TRUE.equals(cancelledTasks.get(taskId))) {
            throw new CancelledException();
        }
    }

    private void addTokens(AiWhiteboxTask task, int tokens) {
        long current = task.getTokensUsed() != null ? task.getTokensUsed() : 0;
        task.setTokensUsed(current + tokens);
    }

    private String readRepoFile(File repoDir, String filePath) {
        try {
            File file = new File(repoDir, filePath);
            if (file.exists()) {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("读取仓库文件失败: {}", filePath, e);
        }
        return "";
    }

    private List<Map<String, Object>> loadRequirementItems(AiWhiteboxTask task) {
        if (task.getRequirementVersionId() == null) {
            return Collections.emptyList();
        }
        List<RequirementItem> items = requirementItemMapper.selectList(
                new LambdaQueryWrapper<RequirementItem>()
                        .eq(RequirementItem::getVersionId, task.getRequirementVersionId())
                        .orderByAsc(RequirementItem::getSortOrder));
        List<Map<String, Object>> result = new ArrayList<>();
        for (RequirementItem item : items) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", item.getId());
            map.put("title", item.getTitle());
            map.put("description", item.getDescription());
            map.put("reqType", item.getReqType());
            result.add(map);
        }
        return result;
    }

    private List<Map<String, Object>> resolveRelatedItems(AiWhiteboxMethod method,
                                                          List<Map<String, Object>> allItems) {
        if (method.getRelatedRequirementsJson() == null || allItems.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            List<Long> ids = objectMapper.readValue(method.getRelatedRequirementsJson(),
                    new TypeReference<List<Long>>() {});
            List<Map<String, Object>> related = new ArrayList<>();
            for (Map<String, Object> item : allItems) {
                if (ids.contains(((Number) item.get("id")).longValue())) {
                    related.add(item);
                }
            }
            return related;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private Map<String, List<AiWhiteboxMethod>> groupByClass(List<AiWhiteboxMethod> methods) {
        // 仅对直接变更方法（MODIFIED/ADDED）生成测试；INDIRECT 仅作影响提示
        Map<String, List<AiWhiteboxMethod>> groups = new LinkedHashMap<>();
        for (AiWhiteboxMethod method : methods) {
            if ("INDIRECT".equals(method.getChangeType())) {
                continue;
            }
            groups.computeIfAbsent(method.getClassName(), k -> new ArrayList<>()).add(method);
        }
        return groups;
    }

    private Long findMethodId(List<AiWhiteboxMethod> classMethods, String sourceMethod) {
        if (sourceMethod != null) {
            for (AiWhiteboxMethod method : classMethods) {
                if (sourceMethod.equals(method.getMethodName())
                        || method.getMethodName().startsWith(sourceMethod)) {
                    return method.getId();
                }
            }
        }
        return classMethods.get(0).getId();
    }

    private String normalizePriority(String priority) {
        if ("高".equals(priority) || "中".equals(priority) || "低".equals(priority)) {
            return priority;
        }
        return "中";
    }

    private String joinSteps(List<String> steps) {
        if (steps == null || steps.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(i + 1).append(". ").append(steps.get(i));
        }
        return sb.toString();
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return null;
        }
    }

    private String simpleName(String className) {
        int idx = className.lastIndexOf('.');
        return idx >= 0 ? className.substring(idx + 1) : className;
    }

    private String packageName(String className) {
        int idx = className.lastIndexOf('.');
        return idx >= 0 ? className.substring(0, idx) : "";
    }

    /**
     * 计算测试类文件路径：源文件所属 Maven 模块（向上查找 pom.xml）的 src/test/java/{package}/
     */
    private File resolveTestFile(File repoDir, String sourceFilePath, String className, String testClassName) {
        File srcFile = new File(repoDir, sourceFilePath);
        File dir = srcFile.getParentFile();
        while (dir != null && new File(dir, "pom.xml").exists() == false) {
            if (dir.equals(repoDir) || dir.getParentFile() == null) {
                break;
            }
            dir = dir.getParentFile();
        }
        File moduleRoot = (dir != null && new File(dir, "pom.xml").exists()) ? dir : repoDir;
        String packagePath = packageName(className).replace('.', '/');
        return new File(new File(moduleRoot, "src/test/java"), packagePath + "/" + testClassName + ".java");
    }

    private List<File> collectModulePoms(File repoDir, Collection<BuildTestRunner.TestClassInfo> testClasses) {
        Set<String> modulePaths = new LinkedHashSet<>();
        for (BuildTestRunner.TestClassInfo info : testClasses) {
            File dir = info.getTestFile().getParentFile();
            while (dir != null) {
                if (new File(dir, "pom.xml").exists()) {
                    modulePaths.add(new File(dir, "pom.xml").getPath());
                    break;
                }
                if (dir.equals(repoDir) || dir.getParentFile() == null) {
                    break;
                }
                dir = dir.getParentFile();
            }
        }
        List<File> poms = new ArrayList<>();
        for (String path : modulePaths) {
            poms.add(new File(path));
        }
        return poms;
    }

    /**
     * 回填编译状态
     */
    private void updateCompileStatus(Long taskId, List<BuildTestRunner.TestClassInfo> testClasses, boolean success) {
        String status = success ? "PASSED" : "FAILED";
        List<AiWhiteboxTest> tests = testMapper.selectList(new LambdaQueryWrapper<AiWhiteboxTest>()
                .eq(AiWhiteboxTest::getTaskId, taskId));
        for (AiWhiteboxTest test : tests) {
            if (!"PASSED".equals(test.getCompileStatus())) {
                test.setCompileStatus(status);
                testMapper.updateById(test);
            }
        }
    }

    /**
     * 回填执行状态（修复轮次耗尽仍失败 → 标记疑似 Bug）
     */
    private void applyTestResults(Long taskId, BuildTestRunner.TestResult result) {
        List<AiWhiteboxTest> tests = testMapper.selectList(new LambdaQueryWrapper<AiWhiteboxTest>()
                .eq(AiWhiteboxTest::getTaskId, taskId));
        for (AiWhiteboxTest test : tests) {
            String key = test.getTestClassName() + "#" + test.getTestMethodName();
            String status = result.getMethodStatusMap().get(key);
            if (status == null) {
                // surefire 未记录（内部类/参数化等），保守标记 SKIPPED
                test.setExecStatus("SKIPPED");
                testMapper.updateById(test);
                continue;
            }
            test.setExecStatus(status);
            String message = result.getMethodMessageMap().get(key);
            if ("FAILED".equals(status)) {
                test.setExecMessage(message != null ? "[疑似Bug] " + message : "[疑似Bug] 测试持续失败");
            } else if (message != null) {
                test.setExecMessage(message);
            }
            testMapper.updateById(test);
        }
    }

    /**
     * 从测试类源码中提取指定测试方法的代码（含注解）
     */
    private String extractTestMethodCode(String classCode, String testMethodName) {
        if (classCode == null || testMethodName == null) {
            return classCode;
        }
        try {
            int idx = classCode.indexOf(testMethodName + "(");
            if (idx < 0) {
                return classCode;
            }
            // 方法名前必须是非标识符字符（避免前缀误匹配）
            if (idx > 0 && Character.isJavaIdentifierPart(classCode.charAt(idx - 1))) {
                int next = classCode.indexOf(testMethodName + "(", idx + 1);
                if (next < 0) {
                    return classCode;
                }
                idx = next;
            }
            // 向上回溯包含注解/Javadoc（最多 10 行或遇到 }
            int start = classCode.lastIndexOf('\n', idx);
            int probe = start;
            for (int i = 0; i < 10; i++) {
                int prev = classCode.lastIndexOf('\n', probe - 1);
                if (prev < 0) {
                    break;
                }
                String line = classCode.substring(prev + 1, probe).trim();
                if (line.startsWith("@") || line.startsWith("*") || line.startsWith("/*") || line.startsWith("//")) {
                    start = prev;
                    probe = prev;
                } else {
                    break;
                }
            }
            // 向下大括号配对
            int depth = 0;
            int end = -1;
            boolean opened = false;
            for (int i = idx; i < classCode.length(); i++) {
                char c = classCode.charAt(i);
                if (c == '{') {
                    depth++;
                    opened = true;
                } else if (c == '}') {
                    depth--;
                    if (opened && depth == 0) {
                        end = i + 1;
                        break;
                    }
                }
            }
            if (end > start) {
                return classCode.substring(start + 1, end);
            }
            return classCode;
        } catch (Exception e) {
            return classCode;
        }
    }

    /**
     * 任务取消异常（内部使用）
     */
    private static class CancelledException extends RuntimeException {
        CancelledException() {
            super("任务已取消");
        }
    }
}
