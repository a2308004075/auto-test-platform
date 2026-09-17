/**
 * @author HXN
 * @date 2026-09-15
 * @description Markdown / JSON 报告生成器
 */
package com.platform.ai.engine;

import com.platform.ai.entity.AiPentestEndpoint;
import com.platform.ai.entity.AiPentestReport;
import com.platform.ai.entity.AiPentestTask;
import com.platform.ai.entity.AiPentestVulnerability;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 渗透测试报告生成器
 *
 * <p>生成 Markdown 和 JSON 两种格式报告，存储到 ai_pentest_report 表。</p>
 */
@Slf4j
@Component
public class ReportGenerator {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 生成 Markdown 格式报告
     */
    public AiPentestReport generateMarkdown(AiPentestTask task,
                                             List<AiPentestEndpoint> endpoints,
                                             List<AiPentestVulnerability> vulns) {
        StringBuilder md = new StringBuilder();

        // 标题与执行摘要
        md.append("# AI 渗透测试报告\n\n");
        md.append("**生成时间**: ").append(LocalDateTime.now().format(DT_FMT)).append("\n\n");
        md.append("## 执行摘要\n\n");
        md.append("| 指标 | 数值 |\n");
        md.append("|---|---|\n");
        md.append("| 扫描任务 ID | ").append(task.getId()).append(" |\n");
        md.append("| 目标环境 | ").append(task.getEnvUrl()).append(" |\n");
        md.append("| 发现端点总数 | ").append(safeInt(task.getTotalEndpoints())).append(" |\n");
        md.append("| 可达端点数 | ").append(safeInt(task.getReachableEndpoints())).append(" |\n");
        md.append("| 已验证漏洞数 | ").append(safeInt(task.getVerifiedVulns())).append(" |\n");
        if (task.getStartedAt() != null && task.getCompletedAt() != null) {
            long seconds = java.time.Duration.between(task.getStartedAt(), task.getCompletedAt()).getSeconds();
            md.append("| 扫描耗时 | ").append(formatDuration(seconds)).append(" |\n");
        }
        md.append("\n");

        // 漏洞汇总
        md.append("## 漏洞汇总\n\n");
        if (vulns.isEmpty()) {
            md.append("本次扫描未发现已验证漏洞。\n\n");
        } else {
            // 按严重级别分组统计
            long critical = vulns.stream().filter(v -> "critical".equals(v.getSeverity())).count();
            long high = vulns.stream().filter(v -> "high".equals(v.getSeverity())).count();
            long medium = vulns.stream().filter(v -> "medium".equals(v.getSeverity())).count();
            long low = vulns.stream().filter(v -> "low".equals(v.getSeverity())).count();

            md.append("| 严重级别 | 数量 |\n");
            md.append("|---|---|\n");
            md.append("| 严重 (Critical) | ").append(critical).append(" |\n");
            md.append("| 高危 (High) | ").append(high).append(" |\n");
            md.append("| 中危 (Medium) | ").append(medium).append(" |\n");
            md.append("| 低危 (Low) | ").append(low).append(" |\n\n");
        }

        // 漏洞详情
        if (!vulns.isEmpty()) {
            md.append("## 漏洞详情\n\n");
            for (int i = 0; i < vulns.size(); i++) {
                AiPentestVulnerability v = vulns.get(i);
                md.append("### ").append(i + 1).append(". ").append(v.getTitle()).append("\n\n");
                md.append("| 属性 | 值 |\n");
                md.append("|---|---|\n");
                md.append("| 类型 | ").append(v.getVulnType()).append(" |\n");
                md.append("| 严重级别 | ").append(v.getSeverity()).append(" |\n");
                md.append("| CVSS 评分 | ").append(v.getCvssScore()).append(" |\n");
                md.append("| OWASP 分类 | ").append(v.getOwaspCategory()).append(" |\n");
                md.append("| 受影响端点 | `").append(v.getEndpointPath()).append("` |\n");
                if (v.getSourceFile() != null) {
                    md.append("| 源码位置 | `").append(v.getSourceFile());
                    if (v.getSourceLine() != null) {
                        md.append(":").append(v.getSourceLine());
                    }
                    md.append("` |\n");
                }
                md.append("\n");

                md.append("**描述**: ").append(v.getDescription()).append("\n\n");

                if (v.getPocCommand() != null) {
                    md.append("**PoC 复现命令**:\n```bash\n").append(v.getPocCommand()).append("\n```\n\n");
                }
                if (v.getPocResponse() != null) {
                    String resp = v.getPocResponse();
                    if (resp.length() > 1000) resp = resp.substring(0, 1000) + "\n... (已截断)";
                    md.append("**PoC 响应**:\n```\n").append(resp).append("\n```\n\n");
                }
                md.append("---\n\n");
            }
        }

        // 发现的端点列表
        md.append("## 发现端点列表\n\n");
        md.append("| # | 方法 | 路径 | 框架 | 可达 | 状态码 |\n");
        md.append("|---|---|---|---|---|---|\n");
        for (int i = 0; i < endpoints.size(); i++) {
            AiPentestEndpoint ep = endpoints.get(i);
            md.append("| ").append(i + 1).append(" | ").append(ep.getHttpMethod());
            md.append(" | `").append(ep.getPath()).append("`");
            md.append(" | ").append(ep.getFramework() != null ? ep.getFramework() : "-");
            md.append(" | ").append(ep.getIsReachable() != null && ep.getIsReachable() == 1 ? "是" : "否");
            md.append(" | ").append(ep.getStatusCode() != null ? ep.getStatusCode() : "-");
            md.append(" |\n");
        }
        md.append("\n");

        // 尾部
        md.append("---\n*本报告由 AI 渗透测试引擎自动生成，仅供安全评估参考。*\n");

        String content = md.toString();
        AiPentestReport report = new AiPentestReport();
        report.setTaskId(task.getId());
        report.setReportFormat("markdown");
        report.setReportContent(content);
        report.setFileName("pentest-report-" + task.getId() + ".md");
        report.setFileSize((long) content.getBytes(StandardCharsets.UTF_8).length);
        return report;
    }

    /**
     * 生成 JSON 格式报告
     */
    public AiPentestReport generateJson(AiPentestTask task,
                                         List<AiPentestEndpoint> endpoints,
                                         List<AiPentestVulnerability> vulns) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"taskId\": ").append(task.getId()).append(",\n");
        json.append("  \"envUrl\": \"").append(escapeJson(task.getEnvUrl())).append("\",\n");
        json.append("  \"totalEndpoints\": ").append(safeInt(task.getTotalEndpoints())).append(",\n");
        json.append("  \"reachableEndpoints\": ").append(safeInt(task.getReachableEndpoints())).append(",\n");
        json.append("  \"verifiedVulns\": ").append(safeInt(task.getVerifiedVulns())).append(",\n");
        json.append("  \"generatedAt\": \"").append(LocalDateTime.now().format(DT_FMT)).append("\",\n");

        // 漏洞列表
        json.append("  \"vulnerabilities\": [\n");
        for (int i = 0; i < vulns.size(); i++) {
            AiPentestVulnerability v = vulns.get(i);
            json.append("    {\n");
            json.append("      \"type\": \"").append(escapeJson(v.getVulnType())).append("\",\n");
            json.append("      \"severity\": \"").append(escapeJson(v.getSeverity())).append("\",\n");
            json.append("      \"title\": \"").append(escapeJson(v.getTitle())).append("\",\n");
            json.append("      \"description\": \"").append(escapeJson(v.getDescription())).append("\",\n");
            json.append("      \"endpoint\": \"").append(escapeJson(v.getEndpointPath())).append("\",\n");
            json.append("      \"cvss\": ").append(v.getCvssScore()).append(",\n");
            json.append("      \"owasp\": \"").append(escapeJson(v.getOwaspCategory())).append("\",\n");
            json.append("      \"poc\": \"").append(escapeJson(v.getPocCommand())).append("\"\n");
            json.append("    }").append(i < vulns.size() - 1 ? "," : "").append("\n");
        }
        json.append("  ],\n");

        // 端点列表
        json.append("  \"endpoints\": [\n");
        for (int i = 0; i < endpoints.size(); i++) {
            AiPentestEndpoint ep = endpoints.get(i);
            json.append("    {\"method\": \"").append(ep.getHttpMethod());
            json.append("\", \"path\": \"").append(escapeJson(ep.getPath()));
            json.append("\", \"framework\": \"").append(escapeJson(ep.getFramework()));
            json.append("\", \"reachable\": ").append(ep.getIsReachable() != null && ep.getIsReachable() == 1);
            json.append(", \"statusCode\": ").append(ep.getStatusCode() != null ? ep.getStatusCode() : "null");
            json.append("}").append(i < endpoints.size() - 1 ? "," : "").append("\n");
        }
        json.append("  ]\n");
        json.append("}");

        String content = json.toString();
        AiPentestReport report = new AiPentestReport();
        report.setTaskId(task.getId());
        report.setReportFormat("json");
        report.setReportContent(content);
        report.setFileName("pentest-report-" + task.getId() + ".json");
        report.setFileSize((long) content.getBytes(StandardCharsets.UTF_8).length);
        return report;
    }

    private int safeInt(Integer val) {
        return val != null ? val : 0;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String formatDuration(long seconds) {
        if (seconds < 60) return seconds + " 秒";
        long minutes = seconds / 60;
        long secs = seconds % 60;
        if (minutes < 60) return minutes + " 分 " + secs + " 秒";
        long hours = minutes / 60;
        long mins = minutes % 60;
        return hours + " 时 " + mins + " 分 " + secs + " 秒";
    }
}
