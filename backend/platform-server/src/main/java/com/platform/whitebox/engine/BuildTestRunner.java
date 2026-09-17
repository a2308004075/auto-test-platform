/**
 * @author HXN
 * @date 2026-09-15
 * @description 构建与测试执行器（编译→执行→失败 LLM 修复循环）
 */
package com.platform.whitebox.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.knowledge.pipeline.LlmClient;
import com.platform.whitebox.config.AiWhiteboxConfig;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;

/**
 * 构建与测试执行器
 *
 * <p>编排被测项目的 mvnw 构建流程：
 * <ul>
 *   <li><b>build</b>：注入插件 → test-compile → 失败 LLM 修复循环（≤compile-fix-rounds）</li>
 *   <li><b>test</b>：mvnw test -Dtest=... → 解析 surefire XML 回填用例状态 → 断言失败 LLM 修复循环（≤exec-fix-rounds）</li>
 *   <li><b>quality</b>：jacoco report + pitest mutationCoverage 产物生成</li>
 * </ul>
 */
@Slf4j
@Component
public class BuildTestRunner {

    private final MavenProcessRunner mavenRunner;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final AiWhiteboxConfig config;

    public BuildTestRunner(MavenProcessRunner mavenRunner, LlmClient llmClient,
                           ObjectMapper objectMapper, AiWhiteboxConfig config) {
        this.mavenRunner = mavenRunner;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.config = config;
    }

    // ===== 数据结构 =====

    /** 测试类信息（引擎写入文件后传入） */
    @Data
    public static class TestClassInfo {
        /** 测试类完整名（com.foo.BarWhiteboxTest） */
        private String testClassName;
        /** 测试源文件路径 */
        private File testFile;
        /** 当前测试类源码（修复循环中更新） */
        private String sourceCode;
    }

    /** build 阶段结果 */
    @Data
    public static class BuildResult {
        private boolean success;
        /** 编译通过的测试类数 */
        private int compilePass;
        /** 修复循环消耗 token */
        private int tokensUsed;
    }

    /** test 阶段结果 */
    @Data
    public static class TestResult {
        private int execPass;
        private int execFail;
        /** key = testClass#testMethod → PASSED/FAILED/SKIPPED */
        private Map<String, String> methodStatusMap = new HashMap<>();
        /** key = testClass#testMethod → 失败信息 */
        private Map<String, String> methodMessageMap = new HashMap<>();
        /** 修复循环消耗 token */
        private int tokensUsed;
    }

    // ===== 插件注入 / 还原 =====

    /**
     * 注入覆盖率插件到模块 pom 集合
     */
    public void injectPlugins(List<File> modulePoms, Consumer<String> logger) {
        for (File pom : modulePoms) {
            mavenRunner.injectCoveragePlugins(pom);
            logger.accept("已注入 JaCoCo/PiTest 插件: " + pom.getPath());
        }
    }

    /**
     * 还原所有模块 pom 备份
     */
    public void restorePoms(List<File> modulePoms, Consumer<String> logger) {
        for (File pom : modulePoms) {
            mavenRunner.restorePom(pom);
        }
        logger.accept("已还原被测项目 pom.xml");
    }

    // ===== build 阶段 =====

    /**
     * 编译测试代码（含失败修复循环）
     */
    public BuildResult runBuildPhase(Long taskId, File repoRoot, List<TestClassInfo> testClasses,
                                     Consumer<String> logger, Runnable cancelChecker) throws IOException, InterruptedException {
        BuildResult result = new BuildResult();
        if (testClasses.isEmpty()) {
            result.setSuccess(true);
            return result;
        }

        int maxRounds = Math.max(1, config.getCompileFixRounds());
        for (int round = 0; round < maxRounds; round++) {
            cancelChecker.run();
            if (round > 0) {
                logger.accept("编译修复第 " + round + "/" + (maxRounds - 1) + " 轮重试...");
            }

            MavenProcessRunner.CommandResult compile = mavenRunner.runCommand(
                    taskId, repoRoot, logger, "test-compile", "-q", "-DskipTests");
            if (compile.isSuccess()) {
                result.setSuccess(true);
                result.setCompilePass(testClasses.size());
                logger.accept("测试代码编译通过: " + testClasses.size() + " 个测试类");
                return result;
            }

            // 最后一轮失败不再修复
            if (round == maxRounds - 1) {
                break;
            }

            // 提取编译错误并交 LLM 修复
            String errorLines = extractErrorLines(compile.getOutput());
            logger.accept("编译失败，尝试 LLM 修复（错误摘要 " + errorLines.length() + " 字符）...");
            int tokens = fixTestClasses(testClasses, "编译错误", errorLines, null);
            result.setTokensUsed(result.getTokensUsed() + tokens);
            if (tokens == 0) {
                logger.accept("LLM 修复调用失败，结束修复循环");
                break;
            }
        }

        // 修复循环耗尽：整体编译验证一次（可能最后一轮修复已生效）
        cancelChecker.run();
        MavenProcessRunner.CommandResult finalCompile = mavenRunner.runCommand(
                taskId, repoRoot, logger, "test-compile", "-q", "-DskipTests");
        if (finalCompile.isSuccess()) {
            result.setSuccess(true);
            result.setCompilePass(testClasses.size());
            logger.accept("测试代码编译通过（修复后）: " + testClasses.size() + " 个测试类");
        } else {
            result.setSuccess(false);
            result.setCompilePass(0);
            logger.accept("编译修复循环结束仍失败，标记编译失败");
        }
        return result;
    }

    // ===== test 阶段 =====

    /**
     * 执行测试（含断言失败修复循环）
     */
    public TestResult runTestPhase(Long taskId, File repoRoot, List<TestClassInfo> testClasses,
                                   Consumer<String> logger, Runnable cancelChecker) throws IOException, InterruptedException {
        TestResult result = new TestResult();
        if (testClasses.isEmpty()) {
            return result;
        }

        List<TestClassInfo> runnableClasses = new ArrayList<>(testClasses);
        int maxRounds = Math.max(1, config.getExecFixRounds());

        for (int round = 0; round < maxRounds; round++) {
            cancelChecker.run();
            if (round > 0) {
                logger.accept("执行修复第 " + round + "/" + (maxRounds - 1) + " 轮重试...");
            }

            // 执行测试（-Dtest 限定生成的测试类）
            String testPattern = joinTestClassNames(runnableClasses);
            MavenProcessRunner.CommandResult testRun = mavenRunner.runCommand(
                    taskId, repoRoot, logger, "test",
                    "-Dtest=" + testPattern, "-DfailIfNoTests=false");

            // 解析 surefire 结果
            result = collectTestResults(runnableClasses);

            if (testRun.isSuccess() && result.getExecFail() == 0) {
                logger.accept("测试执行完成: " + result.getExecPass() + " 通过, 0 失败");
                return result;
            }

            logger.accept("测试执行: " + result.getExecPass() + " 通过, " + result.getExecFail() + " 失败");

            // 最后一轮失败不再修复
            if (round == maxRounds - 1 || result.getExecFail() == 0) {
                break;
            }

            // LLM 修复失败用例（仅传失败测试类）
            List<TestClassInfo> failedClasses = filterFailedClasses(runnableClasses, result);
            if (failedClasses.isEmpty()) {
                break;
            }
            String failureDetails = buildFailureDetails(result);
            logger.accept("断言/异常失败 " + result.getExecFail() + " 条，尝试 LLM 修复...");
            int tokens = fixTestClasses(failedClasses, "测试执行失败", failureDetails, result);
            result.setTokensUsed(result.getTokensUsed() + tokens);
            if (tokens == 0) {
                logger.accept("LLM 修复调用失败，结束修复循环");
                break;
            }
        }

        return result;
    }

    /**
     * 汇总 surefire 结果到 TestResult（仅统计指定测试类）
     */
    private TestResult collectTestResults(List<TestClassInfo> testClasses) {
        TestResult result = new TestResult();
        Set<String> classNames = new HashSet<>();
        for (TestClassInfo info : testClasses) {
            classNames.add(info.getTestClassName());
        }
        // surefire classname 可能是内部类形式，前缀匹配
        List<MavenProcessRunner.SurefireTestCase> reports = new ArrayList<>();
        try {
            reports = mavenRunner.parseSurefireReports(getRepoRoot(testClasses));
        } catch (Exception e) {
            log.warn("解析 surefire 报告失败", e);
        }
        for (MavenProcessRunner.SurefireTestCase tc : reports) {
            if (!matchClass(tc.getClassName(), classNames)) {
                continue;
            }
            String key = tc.getClassName() + "#" + tc.getMethodName();
            result.getMethodStatusMap().put(key, tc.getStatus());
            if (tc.getMessage() != null) {
                result.getMethodMessageMap().put(key, tc.getMessage());
            }
            if ("PASSED".equals(tc.getStatus())) {
                result.setExecPass(result.getExecPass() + 1);
            } else if ("FAILED".equals(tc.getStatus())) {
                result.setExecFail(result.getExecFail() + 1);
            }
        }
        return result;
    }

    private File getRepoRoot(List<TestClassInfo> testClasses) {
        // 以第一个测试文件的 Maven 模块根（pom 所在目录）向上探测仓库根（含 .git 的目录）
        File file = testClasses.get(0).getTestFile();
        File dir = file != null ? file.getParentFile() : null;
        while (dir != null) {
            if (new File(dir, ".git").exists()) {
                return dir;
            }
            dir = dir.getParentFile();
        }
        return testClasses.get(0).getTestFile() != null
                ? testClasses.get(0).getTestFile().getParentFile() : new File(".");
    }

    private boolean matchClass(String reportClassName, Set<String> targetClasses) {
        if (targetClasses.contains(reportClassName)) {
            return true;
        }
        for (String target : targetClasses) {
            if (reportClassName != null && reportClassName.startsWith(target + "$")) {
                return true;
            }
        }
        return false;
    }

    private String joinTestClassNames(List<TestClassInfo> testClasses) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < testClasses.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(testClasses.get(i).getTestClassName());
        }
        return sb.toString();
    }

    private List<TestClassInfo> filterFailedClasses(List<TestClassInfo> testClasses, TestResult result) {
        Set<String> failedClassNames = new HashSet<>();
        for (Map.Entry<String, String> entry : result.getMethodStatusMap().entrySet()) {
            if ("FAILED".equals(entry.getValue())) {
                failedClassNames.add(entry.getKey().substring(0, entry.getKey().indexOf('#')));
            }
        }
        List<TestClassInfo> failed = new ArrayList<>();
        for (TestClassInfo info : testClasses) {
            if (failedClassNames.contains(info.getTestClassName())) {
                failed.add(info);
            }
        }
        return failed;
    }

    private String buildFailureDetails(TestResult result) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : result.getMethodStatusMap().entrySet()) {
            if (!"FAILED".equals(entry.getValue())) {
                continue;
            }
            sb.append("== ").append(entry.getKey()).append(" ==\n");
            String message = result.getMethodMessageMap().get(entry.getKey());
            if (message != null) {
                sb.append(message.length() > 2000 ? message.substring(0, 2000) : message).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 提取编译输出中的 [ERROR] 行
     */
    private String extractErrorLines(String output) {
        StringBuilder sb = new StringBuilder();
        for (String line : output.split("\n")) {
            if (line.startsWith("[ERROR]")) {
                sb.append(line.trim()).append('\n');
            }
        }
        String errors = sb.toString();
        return errors.length() > 4000 ? errors.substring(0, 4000) : errors;
    }

    /**
     * LLM 修复测试类源码（回写文件）
     *
     * @return 消耗 token 数（0=调用失败）
     */
    private int fixTestClasses(List<TestClassInfo> testClasses, String errorType,
                               String errorDetails, TestResult testResult) {
        try {
            StringBuilder codeSection = new StringBuilder();
            for (TestClassInfo info : testClasses) {
                codeSection.append("### 测试类 ").append(info.getTestClassName()).append("\n")
                        .append("```java\n").append(info.getSourceCode()).append("\n```\n\n");
            }

            String systemPrompt = "你是 Java 单元测试修复专家。根据编译错误或测试失败信息修复测试代码。" +
                    "注意：不要修改被测类源码，只修复测试类。保持测试意图不变（断言的业务预期不变），" +
                    "仅修正语法错误、API 误用、Mock 配置不当等问题。" +
                    "仅输出 JSON 数组: [{\"testClassName\":\"完整类名\",\"testClassCode\":\"修复后的完整测试类源码\"}]。";

            String userPrompt = "## 失败类型\n" + errorType + "\n\n## 错误详情\n" +
                    (errorDetails != null ? errorDetails : "无") + "\n\n## 待修复测试类\n" + codeSection +
                    "\n请输出修复后的测试类 JSON 数组。";

            LlmClient.ChatResult chatResult = llmClient.chat(
                    Arrays.asList(LlmClient.ChatMessage.system(systemPrompt),
                            LlmClient.ChatMessage.user(userPrompt)),
                    0.2);

            String content = stripCodeFence(chatResult.content);
            List<Map<String, String>> fixes = objectMapper.readValue(
                    content, new TypeReference<List<Map<String, String>>>() {});

            Map<String, TestClassInfo> classMap = new HashMap<>();
            for (TestClassInfo info : testClasses) {
                classMap.put(info.getTestClassName(), info);
            }
            int applied = 0;
            for (Map<String, String> fix : fixes) {
                String className = fix.get("testClassName");
                String code = fix.get("testClassCode");
                TestClassInfo info = classMap.get(className);
                if (info != null && code != null && !code.trim().isEmpty()) {
                    info.setSourceCode(code);
                    if (info.getTestFile() != null) {
                        Files.write(info.getTestFile().toPath(), code.getBytes(StandardCharsets.UTF_8));
                    }
                    applied++;
                }
            }
            log.info("LLM 修复应用 {}/{} 个测试类", applied, testClasses.size());
            return chatResult.tokensUsed;
        } catch (Exception e) {
            log.warn("LLM 修复测试代码失败: {}", e.getMessage());
            return 0;
        }
    }

    private String stripCodeFence(String content) {
        if (content == null) {
            return "[]";
        }
        String s = content.trim();
        if (s.startsWith("```")) {
            int firstLineEnd = s.indexOf('\n');
            if (firstLineEnd > 0) {
                s = s.substring(firstLineEnd + 1);
            }
            int fenceEnd = s.lastIndexOf("```");
            if (fenceEnd >= 0) {
                s = s.substring(0, fenceEnd);
            }
            s = s.trim();
        }
        int start = s.indexOf('[');
        int end = s.lastIndexOf(']');
        if (start >= 0 && end > start) {
            s = s.substring(start, end + 1);
        }
        return s;
    }

    // ===== quality 阶段 =====

    /**
     * 生成 JaCoCo 覆盖率报告并统计变更类覆盖率
     */
    public MavenProcessRunner.Coverage runCoverageReport(Long taskId, File repoRoot,
                                                         Set<String> targetClasses,
                                                         Consumer<String> logger) throws IOException, InterruptedException {
        mavenRunner.runCommand(taskId, repoRoot, logger,
                "org.jacoco:jacoco-maven-plugin:" + MavenProcessRunner.JACOCO_VERSION + ":report", "-q");
        MavenProcessRunner.Coverage coverage = mavenRunner.parseJacocoCoverage(repoRoot, targetClasses);
        logger.accept("覆盖率统计完成: 行 " + coverage.getLineCoverage() + "%, 分支 " + coverage.getBranchCoverage() + "%");
        return coverage;
    }

    /**
     * 执行 PiTest 变异测试并统计得分
     */
    public MavenProcessRunner.MutationStats runMutationTest(Long taskId, File repoRoot,
                                                            Set<String> targetClasses, Set<String> targetTests,
                                                            Consumer<String> logger) throws IOException, InterruptedException {
        MavenProcessRunner.MutationStats stats = new MavenProcessRunner.MutationStats();
        try {
            mavenRunner.runCommand(taskId, repoRoot, logger,
                    "org.pitest:pitest-maven:mutationCoverage",
                    "-DtargetClasses=" + String.join(",", targetClasses),
                    "-DtargetTests=" + String.join(",", targetTests),
                    "-DoutputFormats=XML",
                    "-DtimestampedReports=false");
            stats = mavenRunner.parseMutationReport(repoRoot);
            logger.accept("变异测试完成: " + stats.getKilled() + "/" + stats.getTotal() + " 被杀死");
        } catch (Exception e) {
            // 变异测试失败不阻塞任务（JUnit4 项目等场景）
            logger.accept("变异测试执行失败（任务不阻塞，记录降级）: " + e.getMessage());
            log.warn("变异测试执行失败", e);
        }
        return stats;
    }
}
