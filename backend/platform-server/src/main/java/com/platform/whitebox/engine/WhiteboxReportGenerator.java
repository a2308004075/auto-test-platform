/**
 * @author HXN
 * @date 2026-09-15
 * @description AI 白盒测试报告生成器（Markdown + JSON 双格式）
 */
package com.platform.whitebox.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.whitebox.entity.AiWhiteboxMethod;
import com.platform.whitebox.entity.AiWhiteboxReport;
import com.platform.whitebox.entity.AiWhiteboxTask;
import com.platform.whitebox.entity.AiWhiteboxTest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 白盒测试报告生成器
 *
 * <p>生成 Markdown 与 JSON 双报告并写入 ai_whitebox_report 表，内容覆盖：
 * 概览统计 / 方法明细 / 用例清单与执行结果 / 覆盖率与变异得分 /
 * 疑似 Bug 清单 / 未覆盖分支 / 降级说明。</p>
 */
@Slf4j
@Component
public class WhiteboxReportGenerator {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper objectMapper;

    public WhiteboxReportGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 生成 Markdown 报告
     */
    public AiWhiteboxReport generateMarkdown(AiWhiteboxTask task, List<AiWhiteboxMethod> methods,
                                             List<AiWhiteboxTest> tests, List<String> degradedNotes) {
        StringBuilder md = new StringBuilder();

        md.append("# AI 白盒测试报告\n\n");
        md.append("> 任务 ID: ").append(task.getId())
                .append("  \n> 仓库: ").append(task.getRepositoryName())
                .append("  \n> 基准 commit: `").append(task.getBaselineCommit() != null ? task.getBaselineCommit() : "-").append("`")
                .append("  \n> HEAD commit: `").append(task.getHeadCommit() != null ? task.getHeadCommit() : "-").append("`")
                .append("  \n> 需求版本: ").append(task.getRequirementVersionName() != null ? task.getRequirementVersionName() : "未选择")
                .append("  \n> 开始时间: ").append(task.getStartedAt() != null ? task.getStartedAt().format(TIME_FORMAT) : "-")
                .append("  \n> 完成时间: ").append(task.getCompletedAt() != null ? task.getCompletedAt().format(TIME_FORMAT) : "-")
                .append("\n\n");

        // ===== 概览统计 =====
        md.append("## 一、概览统计\n\n");
        md.append("| 指标 | 数值 |\n|---|---|\n");
        md.append("| 变更文件数 | ").append(task.getTotalChangedFiles()).append(" |\n");
        md.append("| 变更方法数 | ").append(task.getTotalChangedMethods()).append(" |\n");
        md.append("| 生成用例数 | ").append(task.getTotalCases()).append(" |\n");
        md.append("| 生成测试类数 | ").append(task.getTotalTestClasses()).append(" |\n");
        md.append("| 编译通过测试类 | ").append(task.getCompilePass()).append(" |\n");
        md.append("| 执行通过用例 | ").append(task.getExecPass()).append(" |\n");
        md.append("| 执行失败用例 | ").append(task.getExecFail()).append(" |\n");
        md.append("| 行覆盖率 | ").append(fmt(task.getLineCoverage())).append("% |\n");
        md.append("| 分支覆盖率 | ").append(fmt(task.getBranchCoverage())).append("% |\n");
        md.append("| 变异得分 | ").append(fmt(task.getMutationScore())).append("% (")
                .append(task.getKilledMutants()).append("/").append(task.getTotalMutants()).append(") |\n");
        md.append("| Token 消耗 | ").append(task.getTokensUsed()).append(" |\n\n");

        // ===== 疑似 Bug 清单 =====
        List<AiWhiteboxTest> suspectedBugs = new ArrayList<>();
        for (AiWhiteboxTest test : tests) {
            if (test.getExecMessage() != null && test.getExecMessage().contains("[疑似Bug]")) {
                suspectedBugs.add(test);
            }
        }
        md.append("## 二、疑似 Bug 清单\n\n");
        if (suspectedBugs.isEmpty()) {
            md.append("无（修复循环后无持续失败的用例）。\n\n");
        } else {
            md.append("| 用例标题 | 测试方法 | 失败信息 |\n|---|---|---|\n");
            for (AiWhiteboxTest bug : suspectedBugs) {
                md.append("| ").append(escape(bug.getCaseTitle()))
                        .append(" | ").append(escape(bug.getTestMethodName()))
                        .append(" | ").append(escape(truncate(bug.getExecMessage(), 200))).append(" |\n");
            }
            md.append("\n");
        }

        // ===== 变更方法明细 =====
        md.append("## 三、变更方法明细\n\n");
        md.append("| 类名 | 方法 | 变更类型 | 行号 | 关联需求 |\n|---|---|---|---|---|\n");
        for (AiWhiteboxMethod method : methods) {
            md.append("| ").append(escape(method.getClassName()))
                    .append(" | ").append(escape(method.getMethodName()))
                    .append(" | ").append(method.getChangeType())
                    .append(" | ").append(method.getStartLine()).append("-").append(method.getEndLine())
                    .append(" | ").append(requirementCount(method)).append(" |\n");
        }
        md.append("\n");

        // ===== 用例清单与执行结果 =====
        md.append("## 四、用例清单与执行结果\n\n");
        if (tests.isEmpty()) {
            md.append("无生成用例。\n\n");
        } else {
            md.append("| 用例标题 | 类型 | 优先级 | 测试方法 | 编译 | 执行 | 失败信息 |\n|---|---|---|---|---|---|---|\n");
            for (AiWhiteboxTest test : tests) {
                md.append("| ").append(escape(test.getCaseTitle()))
                        .append(" | ").append(test.getCaseType())
                        .append(" | ").append(test.getPriority())
                        .append(" | ").append(escape(test.getTestMethodName()))
                        .append(" | ").append(statusLabel(test.getCompileStatus()))
                        .append(" | ").append(statusLabel(test.getExecStatus()))
                        .append(" | ").append(escape(truncate(test.getExecMessage(), 150))).append(" |\n");
            }
            md.append("\n");
        }

        // ===== 覆盖率与质量评估 =====
        md.append("## 五、覆盖率与质量评估\n\n");
        int mutationThreshold = 60;
        int branchThreshold = 75;
        md.append("- 行覆盖率: **").append(fmt(task.getLineCoverage())).append("%**\n");
        md.append("- 分支覆盖率: **").append(fmt(task.getBranchCoverage())).append("%**（阈值 ").append(branchThreshold).append("%）\n");
        md.append("- 变异得分: **").append(fmt(task.getMutationScore())).append("%**（阈值 ").append(mutationThreshold).append("%）\n");
        if (task.getTotalMutants() == 0) {
            md.append("- 变异测试未产出结果（可能不支持被测项目测试框架，已降级）\n");
        }
        md.append("\n");

        // ===== 降级说明 =====
        md.append("## 六、降级说明\n\n");
        if (degradedNotes == null || degradedNotes.isEmpty()) {
            md.append("无降级项。\n\n");
        } else {
            for (String note : degradedNotes) {
                md.append("- ").append(escape(note)).append("\n");
            }
            md.append("\n");
        }

        md.append("---\n\n*本报告由 AI 白盒测试引擎自动生成，INDIRECT 影响方法为正则扫描的疑似受影响调用者，建议人工复核。*\n");

        return buildReport(task, "markdown", md.toString());
    }

    /**
     * 生成 JSON 报告
     */
    public AiWhiteboxReport generateJson(AiWhiteboxTask task, List<AiWhiteboxMethod> methods,
                                         List<AiWhiteboxTest> tests, List<String> degradedNotes) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("taskId", task.getId());
            root.put("repositoryName", task.getRepositoryName());
            root.put("baselineCommit", task.getBaselineCommit());
            root.put("headCommit", task.getHeadCommit());
            root.put("requirementVersionName", task.getRequirementVersionName());
            root.put("startedAt", task.getStartedAt() != null ? task.getStartedAt().format(TIME_FORMAT) : null);
            root.put("completedAt", task.getCompletedAt() != null ? task.getCompletedAt().format(TIME_FORMAT) : null);

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("totalChangedFiles", task.getTotalChangedFiles());
            summary.put("totalChangedMethods", task.getTotalChangedMethods());
            summary.put("totalCases", task.getTotalCases());
            summary.put("totalTestClasses", task.getTotalTestClasses());
            summary.put("compilePass", task.getCompilePass());
            summary.put("execPass", task.getExecPass());
            summary.put("execFail", task.getExecFail());
            summary.put("lineCoverage", task.getLineCoverage());
            summary.put("branchCoverage", task.getBranchCoverage());
            summary.put("mutationScore", task.getMutationScore());
            summary.put("totalMutants", task.getTotalMutants());
            summary.put("killedMutants", task.getKilledMutants());
            summary.put("tokensUsed", task.getTokensUsed());
            root.put("summary", summary);

            List<Map<String, Object>> methodList = new ArrayList<>();
            for (AiWhiteboxMethod method : methods) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", method.getId());
                m.put("filePath", method.getFilePath());
                m.put("className", method.getClassName());
                m.put("methodName", method.getMethodName());
                m.put("methodSignature", method.getMethodSignature());
                m.put("changeType", method.getChangeType());
                m.put("startLine", method.getStartLine());
                m.put("endLine", method.getEndLine());
                m.put("relatedRequirementsJson", method.getRelatedRequirementsJson());
                methodList.add(m);
            }
            root.put("methods", methodList);

            List<Map<String, Object>> testList = new ArrayList<>();
            for (AiWhiteboxTest test : tests) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("id", test.getId());
                t.put("methodId", test.getMethodId());
                t.put("testClassName", test.getTestClassName());
                t.put("testMethodName", test.getTestMethodName());
                t.put("caseTitle", test.getCaseTitle());
                t.put("caseType", test.getCaseType());
                t.put("priority", test.getPriority());
                t.put("compileStatus", test.getCompileStatus());
                t.put("execStatus", test.getExecStatus());
                t.put("execMessage", test.getExecMessage());
                t.put("suspectedBug", test.getExecMessage() != null && test.getExecMessage().contains("[疑似Bug]"));
                t.put("savedToManual", Integer.valueOf(1).equals(test.getSavedToManual()));
                testList.add(t);
            }
            root.put("tests", testList);

            root.put("degradedNotes", degradedNotes != null ? degradedNotes : new ArrayList<>());

            return buildReport(task, "json", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (Exception e) {
            log.warn("生成 JSON 报告失败，降级为空对象", e);
            return buildReport(task, "json", "{}");
        }
    }

    private AiWhiteboxReport buildReport(AiWhiteboxTask task, String format, String content) {
        AiWhiteboxReport report = new AiWhiteboxReport();
        report.setTaskId(task.getId());
        report.setReportFormat(format);
        report.setReportContent(content);
        report.setFileName("whitebox-report-task-" + task.getId() + "." + format);
        report.setFileSize((long) content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        report.setCreatedAt(LocalDateTime.now());
        return report;
    }

    private String fmt(BigDecimal value) {
        return value != null ? value.setScale(2, RoundingMode.HALF_UP).toPlainString() : "-";
    }

    private String statusLabel(String status) {
        if (status == null) {
            return "-";
        }
        switch (status) {
            case "PASSED": return "✅ 通过";
            case "FAILED": return "❌ 失败";
            case "SKIPPED": return "⏭ 跳过";
            case "PENDING": return "⏳ 待执行";
            default: return status;
        }
    }

    private String requirementCount(AiWhiteboxMethod method) {
        String json = method.getRelatedRequirementsJson();
        if (json == null || json.isEmpty() || "[]".equals(json)) {
            return "-";
        }
        try {
            List<?> list = objectMapper.readValue(json, List.class);
            return list.size() + " 条";
        } catch (Exception e) {
            return "-";
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text != null ? text : "";
        }
        return text.substring(0, maxLength) + "...";
    }

    private String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("|", "\\|").replace("\n", " ");
    }
}
