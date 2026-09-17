/**
 * @author HXN
 * @date 2026-09-15
 * @description Maven 构建进程执行器（mvnw 命令 + pom 插件注入 + 产物解析）
 */
package com.platform.whitebox.engine;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Maven 构建进程执行器
 *
 * <p>职责：
 * <ul>
 *   <li>执行 mvnw.cmd 命令（Windows），支持超时与取消（进程注册表 destroyForcibly）</li>
 *   <li>pom 临时注入 jacoco-maven-plugin / pitest-maven 插件（DOM 命名空间感知），备份与还原</li>
 *   <li>解析 surefire XML（用例执行结果）、jacoco.xml（覆盖率）、mutations.xml（变异得分）</li>
 * </ul>
 */
@Slf4j
@Component
public class MavenProcessRunner {

    public static final String JACOCO_VERSION = "0.8.11";
    public static final String PITEST_VERSION = "1.15.3";
    public static final String PITEST_JUNIT5_PLUGIN_VERSION = "1.2.1";

    private static final String POM_BACKUP_SUFFIX = ".whitebox.bak";

    /** 运行中进程注册表：taskId → 进程集合（cancel 时强制销毁） */
    private final ConcurrentHashMap<Long, Set<Process>> processRegistry = new ConcurrentHashMap<>();

    @Value("${ai-whitebox.java-home:}")
    private String javaHome;

    @Value("${ai-whitebox.mvn-timeout-seconds:600}")
    private int mvnTimeoutSeconds;

    /**
     * 命令执行结果
     */
    @Data
    public static class CommandResult {
        private int exitCode;
        private String output;

        public boolean isSuccess() {
            return exitCode == 0;
        }
    }

    /**
     * surefire 单个用例结果
     */
    @Data
    public static class SurefireTestCase {
        private String className;
        private String methodName;
        /** PASSED/FAILED/SKIPPED */
        private String status;
        private String message;
    }

    /**
     * 覆盖率统计
     */
    @Data
    public static class Coverage {
        private double lineCoverage;
        private double branchCoverage;
    }

    /**
     * 变异测试统计
     */
    @Data
    public static class MutationStats {
        private int total;
        private int killed;
    }

    // ===== 命令执行 =====

    /**
     * 执行 mvnw 命令
     *
     * @param taskId   任务 ID（进程注册用）
     * @param workDir  工作目录（仓库/模块根）
     * @param logger   日志回调（输出关键行）
     * @param args     mvnw 参数（如 test-compile / test -Dtest=...）
     */
    public CommandResult runCommand(Long taskId, File workDir, Consumer<String> logger, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("cmd");
        command.add("/c");
        command.add("mvnw.cmd");
        command.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        if (javaHome != null && !javaHome.trim().isEmpty()) {
            pb.environment().put("JAVA_HOME", javaHome.trim());
        }

        logger.accept("[MVN] " + String.join(" ", args));
        Process process = pb.start();
        registerProcess(taskId, process);

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), "GBK"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
                // 关键行透传任务日志（ERROR/WARNING/BUILD/Tests）
                if (line.startsWith("[ERROR]") || line.startsWith("[WARNING]")
                        || line.contains("BUILD ") || line.contains("Tests run:")
                        || line.contains("Total time") || line.contains("[INFO] BUILD")) {
                    logger.accept(line.trim());
                }
            }
        } finally {
            unregisterProcess(taskId, process);
        }

        boolean finished = process.waitFor(mvnTimeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            logger.accept("[MVN] 命令超时（" + mvnTimeoutSeconds + "s），已强制终止: " + String.join(" ", args));
            CommandResult result = new CommandResult();
            result.setExitCode(-1);
            result.setOutput(output + "\n[TIMEOUT]");
            return result;
        }

        CommandResult result = new CommandResult();
        result.setExitCode(process.exitValue());
        result.setOutput(output.toString());
        return result;
    }

    private void registerProcess(Long taskId, Process process) {
        processRegistry.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).add(process);
    }

    private void unregisterProcess(Long taskId, Process process) {
        Set<Process> processes = processRegistry.get(taskId);
        if (processes != null) {
            processes.remove(process);
            if (processes.isEmpty()) {
                processRegistry.remove(taskId);
            }
        }
    }

    /**
     * 强制销毁任务的所有运行中进程（cancel 调用）
     */
    public void destroyTaskProcesses(Long taskId) {
        Set<Process> processes = processRegistry.remove(taskId);
        if (processes != null) {
            for (Process process : processes) {
                process.destroyForcibly();
            }
            log.info("已强制终止任务 {} 的 {} 个构建进程", taskId, processes.size());
        }
    }

    // ===== pom 插件注入与还原 =====

    /**
     * 向模块 pom 注入 jacoco + pitest 插件（若未存在），原 pom 备份为 *.whitebox.bak
     *
     * @return true=已注入或已存在；false=注入失败
     */
    public boolean injectCoveragePlugins(File pomFile) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(pomFile);
            Element root = doc.getDocumentElement();

            Element build = firstChildElement(root, "build");
            if (build == null) {
                build = createElement(doc, root, "build");
                // build 需插在 extensions之后/modules之后，直接 append 到 project 末尾 Maven 也可接受
                root.appendChild(build);
            }
            Element plugins = firstChildElement(build, "plugins");
            if (plugins == null) {
                plugins = createElement(doc, root, "plugins");
                build.appendChild(plugins);
            }

            boolean hasJacoco = containsPlugin(plugins, "jacoco-maven-plugin");
            boolean hasPitest = containsPlugin(plugins, "pitest-maven");

            if (!hasJacoco) {
                plugins.appendChild(buildJacocoPlugin(doc, root));
            }
            if (!hasPitest) {
                plugins.appendChild(buildPitestPlugin(doc, root));
            }

            if (hasJacoco && hasPitest) {
                log.info("pom 已含 jacoco/pitest 插件，无需注入: {}", pomFile.getPath());
                return true;
            }

            // 备份原 pom 后写入修改
            File backup = new File(pomFile.getParentFile(), pomFile.getName() + POM_BACKUP_SUFFIX);
            if (!backup.exists()) {
                Files.copy(pomFile.toPath(), backup.toPath());
            }

            writeDocument(doc, pomFile);
            log.info("已注入覆盖率插件: {}", pomFile.getPath());
            return true;
        } catch (Exception e) {
            log.warn("pom 插件注入失败: {}", pomFile.getPath(), e);
            return false;
        }
    }

    /**
     * 还原 pom 备份（若存在）
     */
    public void restorePom(File pomFile) {
        File backup = new File(pomFile.getParentFile(), pomFile.getName() + POM_BACKUP_SUFFIX);
        if (backup.exists()) {
            try {
                Files.copy(backup.toPath(), pomFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.delete(backup.toPath());
                log.info("已还原 pom 备份: {}", pomFile.getPath());
            } catch (IOException e) {
                log.warn("还原 pom 备份失败: {}", pomFile.getPath(), e);
            }
        }
    }

    private boolean containsPlugin(Element plugins, String artifactId) {
        for (Element plugin : childElements(plugins, "plugin")) {
            Element artifact = firstChildElement(plugin, "artifactId");
            if (artifact != null && artifactId.equals(artifact.getTextContent().trim())) {
                return true;
            }
        }
        return false;
    }

    private Element buildJacocoPlugin(Document doc, Element root) {
        Element plugin = createElement(doc, root, "plugin");
        plugin.appendChild(textElement(doc, root, "groupId", "org.jacoco"));
        plugin.appendChild(textElement(doc, root, "artifactId", "jacoco-maven-plugin"));
        plugin.appendChild(textElement(doc, root, "version", JACOCO_VERSION));
        Element executions = createElement(doc, root, "executions");
        Element exec1 = createElement(doc, root, "execution");
        Element goals1 = createElement(doc, root, "goals");
        goals1.appendChild(textElement(doc, root, "goal", "prepare-agent"));
        exec1.appendChild(goals1);
        executions.appendChild(exec1);
        Element exec2 = createElement(doc, root, "execution");
        exec2.appendChild(textElement(doc, root, "id", "report"));
        exec2.appendChild(textElement(doc, root, "phase", "test"));
        Element goals2 = createElement(doc, root, "goals");
        goals2.appendChild(textElement(doc, root, "goal", "report"));
        exec2.appendChild(goals2);
        executions.appendChild(exec2);
        plugin.appendChild(executions);
        return plugin;
    }

    private Element buildPitestPlugin(Document doc, Element root) {
        Element plugin = createElement(doc, root, "plugin");
        plugin.appendChild(textElement(doc, root, "groupId", "org.pitest"));
        plugin.appendChild(textElement(doc, root, "artifactId", "pitest-maven"));
        plugin.appendChild(textElement(doc, root, "version", PITEST_VERSION));
        Element dependencies = createElement(doc, root, "dependencies");
        Element dependency = createElement(doc, root, "dependency");
        dependency.appendChild(textElement(doc, root, "groupId", "org.pitest"));
        dependency.appendChild(textElement(doc, root, "artifactId", "pitest-junit5-plugin"));
        dependency.appendChild(textElement(doc, root, "version", PITEST_JUNIT5_PLUGIN_VERSION));
        dependencies.appendChild(dependency);
        plugin.appendChild(dependencies);
        return plugin;
    }

    private Element createElement(Document doc, Element context, String name) {
        String ns = context.getNamespaceURI();
        return ns != null ? doc.createElementNS(ns, name) : doc.createElement(name);
    }

    private Element textElement(Document doc, Element context, String name, String text) {
        Element element = createElement(doc, context, name);
        element.setTextContent(text);
        return element;
    }

    private Element firstChildElement(Element parent, String name) {
        for (Element child : childElements(parent, name)) {
            return child;
        }
        return null;
    }

    private List<Element> childElements(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        org.w3c.dom.NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node node = children.item(i);
            if (node instanceof Element && name.equals(node.getLocalName() != null ? node.getLocalName() : node.getNodeName())) {
                result.add((Element) node);
            }
        }
        return result;
    }

    private void writeDocument(Document doc, File file) throws Exception {
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.STANDALONE, doc.getXmlStandalone() ? "yes" : "no");
        transformer.transform(new DOMSource(doc), new StreamResult(file));
    }

    // ===== 产物解析 =====

    /**
     * 解析 surefire 测试报告（仓库下所有 target/surefire-reports/TEST-*.xml）
     */
    public List<SurefireTestCase> parseSurefireReports(File repoRoot) {
        List<SurefireTestCase> cases = new ArrayList<>();
        parseSurefireDir(repoRoot, cases);
        return cases;
    }

    private void parseSurefireDir(File dir, List<SurefireTestCase> cases) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                if ("surefire-reports".equals(child.getName())) {
                    File[] reports = child.listFiles((d, name) -> name.startsWith("TEST-") && name.endsWith(".xml"));
                    if (reports != null) {
                        for (File report : reports) {
                            cases.addAll(parseSurefireFile(report));
                        }
                    }
                } else if (!"node_modules".equals(child.getName())) {
                    parseSurefireDir(child, cases);
                }
            }
        }
    }

    private List<SurefireTestCase> parseSurefireFile(File report) {
        List<SurefireTestCase> cases = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document doc = factory.newDocumentBuilder().parse(report);
            org.w3c.dom.NodeList testcases = doc.getElementsByTagName("testcase");
            for (int i = 0; i < testcases.getLength(); i++) {
                Element testcase = (Element) testcases.item(i);
                SurefireTestCase tc = new SurefireTestCase();
                tc.setClassName(testcase.getAttribute("classname"));
                tc.setMethodName(testcase.getAttribute("name"));
                org.w3c.dom.NodeList failures = testcase.getElementsByTagName("failure");
                org.w3c.dom.NodeList errors = testcase.getElementsByTagName("error");
                org.w3c.dom.NodeList skipped = testcase.getElementsByTagName("skipped");
                if (failures.getLength() > 0) {
                    tc.setStatus("FAILED");
                    tc.setMessage(extractMessage((Element) failures.item(0)));
                } else if (errors.getLength() > 0) {
                    tc.setStatus("FAILED");
                    tc.setMessage(extractMessage((Element) errors.item(0)));
                } else if (skipped.getLength() > 0) {
                    tc.setStatus("SKIPPED");
                } else {
                    tc.setStatus("PASSED");
                }
                cases.add(tc);
            }
        } catch (Exception e) {
            log.warn("解析 surefire 报告失败: {}", report.getPath(), e);
        }
        return cases;
    }

    private String extractMessage(Element failureElement) {
        String message = failureElement.getAttribute("message");
        String type = failureElement.getAttribute("type");
        // 附带堆栈首 30 行
        String content = failureElement.getTextContent();
        if (content != null && !content.isEmpty()) {
            String[] lines = content.split("\n");
            StringBuilder sb = new StringBuilder(message != null ? message : type);
            sb.append('\n');
            for (int i = 0; i < Math.min(lines.length, 30); i++) {
                sb.append(lines[i].trim()).append('\n');
            }
            return sb.toString();
        }
        return message != null && !message.isEmpty() ? message : type;
    }

    /**
     * 解析 jacoco.xml 统计指定类的行/分支覆盖率
     *
     * @param targetClasses 完整类名集合（com.foo.Bar）
     */
    public Coverage parseJacocoCoverage(File repoRoot, Set<String> targetClasses) {
        Coverage coverage = new Coverage();
        File jacocoXml = findFile(repoRoot, "jacoco.xml");
        if (jacocoXml == null) {
            log.warn("未找到 jacoco.xml");
            return coverage;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document doc = factory.newDocumentBuilder().parse(jacocoXml);

            // 类名转 jacoco 内部格式：com.foo.Bar → com/foo/Bar
            Set<String> internalNames = new HashSet<>();
            for (String className : targetClasses) {
                internalNames.add(className.replace('.', '/'));
            }

            long lineMissed = 0, lineCovered = 0, branchMissed = 0, branchCovered = 0;
            org.w3c.dom.NodeList classes = doc.getElementsByTagName("class");
            for (int i = 0; i < classes.getLength(); i++) {
                Element clazz = (Element) classes.item(i);
                if (!internalNames.contains(clazz.getAttribute("name"))) {
                    continue;
                }
                for (Element counter : childElements(clazz, "counter")) {
                    String type = counter.getAttribute("type");
                    long missed = Long.parseLong(counter.getAttribute("missed"));
                    long covered = Long.parseLong(counter.getAttribute("covered"));
                    if ("LINE".equals(type)) {
                        lineMissed += missed;
                        lineCovered += covered;
                    } else if ("BRANCH".equals(type)) {
                        branchMissed += missed;
                        branchCovered += covered;
                    }
                }
            }
            if (lineMissed + lineCovered > 0) {
                coverage.setLineCoverage(round2(lineCovered * 100.0 / (lineMissed + lineCovered)));
            }
            if (branchMissed + branchCovered > 0) {
                coverage.setBranchCoverage(round2(branchCovered * 100.0 / (branchMissed + branchCovered)));
            }
        } catch (Exception e) {
            log.warn("解析 jacoco.xml 失败", e);
        }
        return coverage;
    }

    /**
     * 解析 pitest mutations.xml 统计变异得分
     */
    public MutationStats parseMutationReport(File repoRoot) {
        MutationStats stats = new MutationStats();
        File mutationsXml = findFile(repoRoot, "mutations.xml");
        if (mutationsXml == null) {
            log.warn("未找到 mutations.xml（变异测试可能未执行或失败）");
            return stats;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document doc = factory.newDocumentBuilder().parse(mutationsXml);
            org.w3c.dom.NodeList mutations = doc.getElementsByTagName("mutation");
            for (int i = 0; i < mutations.getLength(); i++) {
                Element mutation = (Element) mutations.item(i);
                stats.setTotal(stats.getTotal() + 1);
                if ("KILLED".equals(mutation.getAttribute("status"))) {
                    stats.setKilled(stats.getKilled() + 1);
                }
            }
        } catch (Exception e) {
            log.warn("解析 mutations.xml 失败", e);
        }
        return stats;
    }

    private File findFile(File root, String fileName) {
        // 优先常见位置
        File direct = new File(root, "target/site/jacoco/" + fileName);
        if (direct.exists()) {
            return direct;
        }
        // 递归查找
        return findRecursive(root, fileName, 0);
    }

    private File findRecursive(File dir, String fileName, int depth) {
        if (depth > 6) {
            return null;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            if (child.isFile() && fileName.equals(child.getName())) {
                return child;
            }
        }
        for (File child : children) {
            if (child.isDirectory() && !"node_modules".equals(child.getName()) && !"src".equals(child.getName())) {
                File found = findRecursive(child, fileName, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
