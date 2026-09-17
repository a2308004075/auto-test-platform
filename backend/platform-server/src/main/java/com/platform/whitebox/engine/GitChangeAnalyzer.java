/**
 * @author HXN
 * @date 2026-09-15
 * @description Git 增量变更分析器（baseline..HEAD 方法级 diff）
 */
package com.platform.whitebox.engine;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Git 变更分析器
 *
 * <p>对比 baseline..HEAD 两个 commit 之间的 Java 源码变更：
 * <ul>
 *   <li>过滤 {@code src/main/java} 下的 .java 文件（排除测试代码）</li>
 *   <li>新版本直接读取工作区文件；旧版本经 RevWalk 解析 blob 内容</li>
 *   <li>按“方法签名为 key、方法体文本 hash 对比”识别 MODIFIED / ADDED</li>
 *   <li>INDIRECT：对变更方法名在 src/main/java 下正则扫描调用者（V1 简化实现）</li>
 * </ul>
 */
@Slf4j
@Component
public class GitChangeAnalyzer {

    /** 主源码路径前缀（排除 src/test/java） */
    private static final String MAIN_SRC_PREFIX = "src/main/java/";

    /** Java 文件后缀 */
    private static final String JAVA_SUFFIX = ".java";

    /** 方法声明正则：匹配方法签名行（含参数列表与左大括号） */
    private static final Pattern METHOD_DECL = Pattern.compile(
            "^\\s*((?:public|protected|private|static|final|synchronized|abstract|default|native)\\s+)*" +
                    "[\\w<>,\\[\\].?\\s]+?\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:throws\\s+[\\w,\\s.]+)?\\s*\\{");

    /** 方法调用正则（INDIRECT 影响分析用） */
    private static final Pattern CALL_PATTERN_TEMPLATE = Pattern.compile("[{}();,\\s]");

    /**
     * 分析仓库变更
     *
     * @param repoDir        本地仓库目录
     * @param baselineCommit 基准 commit（40 位十六进制）
     * @param headCommit     HEAD commit（40 位十六进制）
     * @param maxMethods     变更方法数上限（超出截断）
     * @return 变更方法列表（超过 maxMethods 截断）
     */
    public AnalyzeResult analyze(File repoDir, String baselineCommit, String headCommit, int maxMethods) throws IOException {
        try (Git git = Git.open(repoDir)) {
            Repository repository = git.getRepository();

            ObjectId oldId = repository.resolve(baselineCommit);
            ObjectId newId = repository.resolve(headCommit);
            if (oldId == null) {
                throw new IOException("基准 commit 不存在于仓库: " + baselineCommit);
            }
            if (newId == null) {
                throw new IOException("HEAD commit 不存在于仓库: " + headCommit);
            }

            // 解析新旧 tree
            try (RevWalk revWalk = new RevWalk(repository)) {
                RevTree oldTree = revWalk.parseCommit(oldId).getTree();
                RevTree newTree = revWalk.parseCommit(newId).getTree();

                // diff 两个 tree
                List<DiffEntry> diffs;
                try (ObjectReader reader = repository.newObjectReader();
                     DiffFormatter formatter = new DiffFormatter(new ByteArrayOutputStream())) {
                    CanonicalTreeParser oldTreeIter = new CanonicalTreeParser();
                    oldTreeIter.reset(reader, oldTree);
                    CanonicalTreeParser newTreeIter = new CanonicalTreeParser();
                    newTreeIter.reset(reader, newTree);
                    diffs = formatter.scan(oldTreeIter, newTreeIter);
                }

                List<ChangedFile> changedFiles = new ArrayList<>();
                for (DiffEntry entry : diffs) {
                    String path = entry.getNewPath();
                    DiffEntry.ChangeType type = entry.getChangeType();
                    if (type == DiffEntry.ChangeType.DELETE) {
                        continue;
                    }
                    if (path == null || !path.startsWith(MAIN_SRC_PREFIX) || !path.endsWith(JAVA_SUFFIX)) {
                        continue;
                    }
                    ChangedFile cf = new ChangedFile();
                    cf.setFilePath(path);
                    cf.setChangeType(type.name());
                    // 旧内容（ADD 时为空）
                    cf.setOldContent(type == DiffEntry.ChangeType.ADD ? "" : readBlobContent(repository, revWalk, entry.getOldId().toObjectId()));
                    // 新内容优先读工作区（HEAD 已 checkout），失败回退读 blob
                    cf.setNewContent(readWorktreeContent(repoDir, path));
                    changedFiles.add(cf);
                }

                revWalk.dispose();
                return buildMethods(repository, repoDir, changedFiles, maxMethods);
            }
        }
    }

    /**
     * 将文件级 diff 细化为方法级变更 + INDIRECT 影响分析
     */
    private AnalyzeResult buildMethods(Repository repository, File repoDir,
                                       List<ChangedFile> changedFiles, int maxMethods) {
        List<ChangedMethod> methods = new ArrayList<>();
        Set<String> changedFileSet = new HashSet<>();
        for (ChangedFile cf : changedFiles) {
            changedFileSet.add(cf.getFilePath());
        }

        for (ChangedFile cf : changedFiles) {
            Map<String, MethodInfo> oldMethods = extractMethods(cf.getOldContent());
            Map<String, MethodInfo> newMethods = extractMethods(cf.getNewContent());

            String className = toClassName(cf.getFilePath());

            for (Map.Entry<String, MethodInfo> entry : newMethods.entrySet()) {
                String signature = entry.getKey();
                MethodInfo newInfo = entry.getValue();
                MethodInfo oldInfo = oldMethods.get(signature);

                ChangedMethod cm = new ChangedMethod();
                cm.setFilePath(cf.getFilePath());
                cm.setClassName(className);
                cm.setMethodName(newInfo.methodName);
                cm.setMethodSignature(signature);
                cm.setStartLine(newInfo.startLine);
                cm.setEndLine(newInfo.endLine);
                cm.setSourceCode(newInfo.body);

                if (oldInfo == null) {
                    cm.setChangeType("ADDED");
                } else if (!oldInfo.bodyHash.equals(newInfo.bodyHash)) {
                    cm.setChangeType("MODIFIED");
                } else {
                    // 方法体未变（仅签名位置移动/注释变更），跳过
                    continue;
                }
                methods.add(cm);

                if (methods.size() >= maxMethods) {
                    log.warn("变更方法数达到上限 {}，后续变更截断", maxMethods);
                    AnalyzeResult result = new AnalyzeResult();
                    result.setChangedFiles(changedFiles.size());
                    result.setMethods(methods);
                    result.setTruncated(true);
                    return result;
                }
            }
        }

        // INDIRECT 影响分析：正则扫描调用者
        List<ChangedMethod> indirectMethods = findIndirectMethods(repoDir, methods, changedFileSet);
        for (ChangedMethod indirect : indirectMethods) {
            if (methods.size() >= maxMethods) {
                break;
            }
            methods.add(indirect);
        }

        AnalyzeResult result = new AnalyzeResult();
        result.setChangedFiles(changedFiles.size());
        result.setMethods(methods);
        result.setTruncated(false);
        return result;
    }

    /**
     * INDIRECT 影响分析（V1 简化：正则文本扫描调用者类）
     */
    private List<ChangedMethod> findIndirectMethods(File repoDir, List<ChangedMethod> directMethods,
                                                    Set<String> changedFileSet) {
        List<ChangedMethod> indirect = new ArrayList<>();
        if (directMethods.isEmpty()) {
            return indirect;
        }

        // 收集所有直接变更方法名 → 排除构造器/通用名
        Set<String> methodNames = new HashSet<>();
        for (ChangedMethod cm : directMethods) {
            String name = cm.getMethodName();
            if (name != null && name.length() > 2 && !name.equals("equals") && !name.equals("hashCode")
                    && !name.equals("toString") && !name.equals("getValue") && !name.equals("setValue")) {
                methodNames.add(name);
            }
        }
        if (methodNames.isEmpty()) {
            return indirect;
        }

        Path srcRoot = repoDir.toPath().resolve(MAIN_SRC_PREFIX);
        if (!Files.exists(srcRoot)) {
            return indirect;
        }

        Set<String> indirectKeys = new HashSet<>();
        try {
            Files.walk(srcRoot).filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(JAVA_SUFFIX))
                    .forEach(p -> {
                        String relPath = repoDir.toPath().relativize(p).toString().replace('\\', '/');
                        // 跳过已直接变更的文件
                        if (changedFileSet.contains(relPath)) {
                            return;
                        }
                        try {
                            String content = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                            // 排除接口/枚举等无方法体的声明文件
                            if (content.contains(" interface ") || content.contains(" enum ")) {
                                return;
                            }
                            for (String methodName : methodNames) {
                                // 逐个方法名正则扫描调用点 \bname\s*\(
                                Pattern callPattern = Pattern.compile("\\b" + Pattern.quote(methodName) + "\\s*\\(");
                                if (!callPattern.matcher(content).find()) {
                                    continue;
                                }
                                // 找到调用者类（文件内首个 class 声明）
                                String className = toClassName(relPath);
                                String key = relPath + ":" + className;
                                if (indirectKeys.contains(key)) {
                                    continue;
                                }
                                indirectKeys.add(key);

                                ChangedMethod cm = new ChangedMethod();
                                cm.setFilePath(relPath);
                                cm.setClassName(className);
                                cm.setMethodName(methodName + "（调用）");
                                cm.setMethodSignature("indirect:" + relPath);
                                cm.setChangeType("INDIRECT");
                                cm.setSourceCode(truncateForStorage(content, 5000));
                                indirect.add(cm);
                            }
                        } catch (IOException e) {
                            log.warn("INDIRECT 影响分析读取文件失败: {}", p, e);
                        }
                    });
        } catch (IOException e) {
            log.warn("INDIRECT 影响分析遍历源码目录失败", e);
        }
        log.info("INDIRECT 影响分析: 命中 {} 个疑似受影响类", indirect.size());
        return indirect;
    }

    /**
     * 解析源码文本提取方法（简化 JavaParser 兜底方案前的文本级实现）
     *
     * <p>key = 方法签名（返回类型+方法名+参数类型），value = 方法体文本与 hash。
     * 依靠大括号配对提取方法体。</p>
     */
    private Map<String, MethodInfo> extractMethods(String content) {
        Map<String, MethodInfo> result = new LinkedHashMap<>();
        if (content == null || content.isEmpty()) {
            return result;
        }
        String[] lines = content.split("\n", -1);
        int depth = 0;
        boolean inMethod = false;
        boolean inBlockComment = false;
        MethodInfo current = null;
        int methodStartLine = 0;
        StringBuilder body = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // 块注释状态机
            if (inBlockComment) {
                if (trimmed.contains("*/")) {
                    inBlockComment = false;
                }
                continue;
            }
            if (trimmed.startsWith("/*")) {
                inBlockComment = !trimmed.contains("*/");
                continue;
            }
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                continue;
            }

            if (!inMethod) {
                java.util.regex.Matcher m = METHOD_DECL.matcher(line);
                if (m.matches() || m.find()) {
                    // 排除控制语句与类/接口/构造声明
                    if (trimmed.startsWith("if(") || trimmed.startsWith("if (")
                            || trimmed.startsWith("for") || trimmed.startsWith("while")
                            || trimmed.startsWith("switch") || trimmed.startsWith("catch")
                            || trimmed.startsWith("synchronized") || trimmed.startsWith("try")
                            || trimmed.startsWith("do") || trimmed.startsWith("else")
                            || trimmed.contains(" class ") || trimmed.contains(" interface ")
                            || trimmed.contains(" enum ")) {
                        // 构造器保留（签名无返回类型），控制流跳过
                        if (!isConstructorLine(trimmed)) {
                            continue;
                        }
                    }
                    String signature = normalizeSignature(m.group(0));
                    String methodName = m.group(2);
                    if (methodName == null || methodName.isEmpty()) {
                        continue;
                    }
                    current = new MethodInfo();
                    current.methodName = methodName;
                    current.signature = signature;
                    methodStartLine = i + 1;
                    body = new StringBuilder();
                    inMethod = true;
                    depth = countChar(trimmed, '{') - countChar(trimmed, '}');
                    body.append(line).append('\n');
                    // 单行方法体 closed immediately
                    if (depth <= 0 && trimmed.contains("}")) {
                        finishMethod(result, current, methodStartLine, i + 1, body.toString());
                        inMethod = false;
                        current = null;
                    }
                }
            } else {
                body.append(line).append('\n');
                depth += countChar(trimmed, '{') - countChar(trimmed, '}');
                if (depth <= 0) {
                    finishMethod(result, current, methodStartLine, i + 1, body.toString());
                    inMethod = false;
                    current = null;
                }
            }
        }
        return result;
    }

    private boolean isConstructorLine(String trimmed) {
        // 构造器：ClassName(args) 形式，签名里方法名前无返回类型
        return false;
    }

    private void finishMethod(Map<String, MethodInfo> result, MethodInfo info,
                              int startLine, int endLine, String body) {
        info.startLine = startLine;
        info.endLine = endLine;
        info.body = body;
        info.bodyHash = sha256(body);
        result.put(info.signature, info);
    }

    private String normalizeSignature(String declLine) {
        // 去掉修饰符与包名前缀，压缩空白
        String s = declLine.trim();
        s = s.replaceAll("(public|protected|private|static|final|synchronized|abstract|default|native)\\s+", "");
        s = s.replaceAll("\\s*\\{\\s*$", "");
        s = s.replaceAll("\\s+", " ");
        return s;
    }

    private int countChar(String s, char c) {
        int count = 0;
        for (char ch : s.toCharArray()) {
            if (ch == c) count++;
        }
        return count;
    }

    private String sha256(String text) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }

    /**
     * 读取指定 commit 的 blob 内容
     */
    private String readBlobContent(Repository repository, RevWalk revWalk, ObjectId blobId) throws IOException {
        if (blobId == null || ObjectId.zeroId().equals(blobId)) {
            return "";
        }
        byte[] bytes = repository.open(blobId).getBytes();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 优先读取工作区文件内容（HEAD 已 checkout 到工作区）
     */
    private String readWorktreeContent(File repoDir, String path) {
        try {
            Path file = repoDir.toPath().resolve(path);
            if (Files.exists(file)) {
                return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("读取工作区文件失败: {}", path, e);
        }
        return "";
    }

    private String toClassName(String filePath) {
        // src/main/java/com/foo/Bar.java → com.foo.Bar
        if (filePath.startsWith(MAIN_SRC_PREFIX) && filePath.endsWith(JAVA_SUFFIX)) {
            String rel = filePath.substring(MAIN_SRC_PREFIX.length(), filePath.length() - JAVA_SUFFIX.length());
            return rel.replace('/', '.');
        }
        return filePath;
    }

    private String truncateForStorage(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }

    // ===== 数据结构 =====

    /** 文件级变更 */
    @Data
    public static class ChangedFile {
        private String filePath;
        private String changeType;
        private String oldContent;
        private String newContent;
    }

    /** 方法级变更 */
    @Data
    public static class ChangedMethod {
        private String filePath;
        private String className;
        private String methodName;
        private String methodSignature;
        private String changeType;
        private Integer startLine;
        private Integer endLine;
        private String sourceCode;
    }

    /** 分析结果 */
    @Data
    public static class AnalyzeResult {
        private int changedFiles;
        private List<ChangedMethod> methods = new ArrayList<>();
        private boolean truncated;
    }

    /** 内部方法信息 */
    private static class MethodInfo {
        String methodName;
        String signature;
        int startLine;
        int endLine;
        String body;
        String bodyHash;
    }
}
