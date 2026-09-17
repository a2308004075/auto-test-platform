/**
 * @author HXN
 * @date 2026-09-15
 * @description 源码端点提取器（正则匹配多框架路由定义）
 */
package com.platform.ai.engine;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.CloneCommand;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 源码端点提取器
 *
 * <p>通过 JGit 克隆仓库，递归扫描源码文件，使用正则提取路由定义。
 * 支持 Spring Boot / Express.js / Django / Go Gin 等主流框架。</p>
 */
@Slf4j
@Component
public class EndpointExtractor {

    @Value("${ai-pentest.temp-dir:./data/pentest}")
    private String tempDir;

    @Value("${repository.clone-timeout-seconds:300}")
    private int cloneTimeoutSeconds;

    /** 需要扫描的源码文件扩展名 */
    private static final Set<String> SOURCE_EXTENSIONS = new HashSet<>(Arrays.asList(
            ".java", ".js", ".ts", ".py", ".go", ".rb", ".php", ".cs"
    ));

    /** 排除的目录名 */
    private static final Set<String> EXCLUDE_DIRS = new HashSet<>(Arrays.asList(
            "node_modules", ".git", "target", "build", "dist", "__pycache__",
            "vendor", ".mvn", "gradle", ".idea", ".vscode"
    ));

    // ===== Spring Boot 路由正则 =====
    private static final Pattern SPRING_MAPPING = Pattern.compile(
            "@(Request|Get|Post|Put|Delete|Patch)Mapping\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\""
    );
    private static final Pattern SPRING_CLASS_MAPPING = Pattern.compile(
            "@RequestMapping\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\""
    );

    // ===== Express.js 路由正则 =====
    private static final Pattern EXPRESS_ROUTE = Pattern.compile(
            "(?:app|router)\\.(get|post|put|delete|patch)\\s*\\(\\s*['\"]([^'\"]+)['\"]"
    );

    // ===== Django 路由正则 =====
    private static final Pattern DJANGO_PATH = Pattern.compile(
            "(?:path|url|re_path)\\s*\\(\\s*['\"]([^'\"]+)['\"]"
    );

    // ===== Go Gin 路由正则 =====
    private static final Pattern GIN_ROUTE = Pattern.compile(
            "(?:r|router|group|g)\\.(GET|POST|PUT|DELETE|PATCH|Any)\\s*\\(\\s*\"([^\"]+)\""
    );

    /**
     * 克隆仓库并提取端点
     *
     * @param repoUrl 仓库地址
     * @return 提取到的端点列表
     */
    public List<ExtractedEndpoint> extractFromRepo(String repoUrl) {
        Path repoDir = null;
        try {
            repoDir = cloneRepository(repoUrl);
            List<ExtractedEndpoint> endpoints = scanDirectory(repoDir, repoUrl);
            log.info("仓库 {} 提取到 {} 个端点", repoUrl, endpoints.size());
            return endpoints;
        } catch (Exception e) {
            log.error("克隆/扫描仓库失败: {}", repoUrl, e);
            throw new RuntimeException("仓库克隆失败: " + repoUrl + " - " + e.getMessage(), e);
        } finally {
            deleteDirectory(repoDir);
        }
    }

    /**
     * JGit 克隆仓库到临时目录
     */
    private Path cloneRepository(String repoUrl) throws Exception {
        Path baseDir = Paths.get(tempDir, "repos");
        Files.createDirectories(baseDir);
        Path targetDir = baseDir.resolve("scan-" + System.currentTimeMillis() + "-" + repoUrl.hashCode());

        log.info("开始克隆仓库: {} -> {}", repoUrl, targetDir);
        CloneCommand cloneCommand = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(targetDir.toFile());

        try (Git git = cloneCommand.call()) {
            log.info("仓库克隆完成: {}", repoUrl);
        }
        return targetDir;
    }

    /**
     * 递归扫描目录中的源码文件
     */
    private List<ExtractedEndpoint> scanDirectory(Path dir, String repoUrl) throws IOException {
        List<ExtractedEndpoint> endpoints = new ArrayList<>();

        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                String dirName = d.getFileName().toString();
                return EXCLUDE_DIRS.contains(dirName) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String fileName = file.getFileName().toString();
                String ext = getExtension(fileName);
                if (SOURCE_EXTENSIONS.contains(ext)) {
                    try {
                        List<ExtractedEndpoint> found = extractFromFile(file, dir, repoUrl);
                        endpoints.addAll(found);
                    } catch (IOException e) {
                        log.warn("读取源码文件失败: {}", file, e);
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });

        return endpoints;
    }

    /**
     * 从单个源码文件提取端点
     */
    private List<ExtractedEndpoint> extractFromFile(Path file, Path repoRoot, String repoUrl) throws IOException {
        List<ExtractedEndpoint> endpoints = new ArrayList<>();
        List<String> lines = Files.readAllLines(file);
        String relativePath = repoRoot.relativize(file).toString().replace('\\', '/');

        // 先检测类级别的 @RequestMapping 前缀
        String classPrefix = "";
        for (String line : lines) {
            Matcher m = SPRING_CLASS_MAPPING.matcher(line);
            if (m.find()) {
                classPrefix = m.group(1);
                break;
            }
        }

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            int lineNo = i + 1;

            // Spring Boot
            Matcher springMatcher = SPRING_MAPPING.matcher(line);
            if (springMatcher.find()) {
                String httpMethod = springMatcher.group(1).toUpperCase();
                if ("REQUEST".equals(httpMethod)) httpMethod = "GET";
                String path = classPrefix + springMatcher.group(2);
                endpoints.add(buildEndpoint(repoUrl, httpMethod, path, relativePath, lineNo, "Spring Boot"));
                continue;
            }

            // Express.js
            Matcher expressMatcher = EXPRESS_ROUTE.matcher(line);
            if (expressMatcher.find()) {
                endpoints.add(buildEndpoint(repoUrl, expressMatcher.group(1).toUpperCase(),
                        expressMatcher.group(2), relativePath, lineNo, "Express.js"));
                continue;
            }

            // Django
            Matcher djangoMatcher = DJANGO_PATH.matcher(line);
            if (djangoMatcher.find()) {
                String path = djangoMatcher.group(1);
                // Django path 中可能包含 <type:name> 格式的参数
                path = path.replaceAll("<\\w+:?(\\w+)>", ":$1");
                endpoints.add(buildEndpoint(repoUrl, "GET", "/" + path, relativePath, lineNo, "Django"));
                continue;
            }

            // Go Gin
            Matcher ginMatcher = GIN_ROUTE.matcher(line);
            if (ginMatcher.find()) {
                String method = ginMatcher.group(1);
                if ("Any".equalsIgnoreCase(method)) method = "GET";
                endpoints.add(buildEndpoint(repoUrl, method.toUpperCase(),
                        ginMatcher.group(2), relativePath, lineNo, "Go Gin"));
            }
        }

        return endpoints;
    }

    private ExtractedEndpoint buildEndpoint(String repoUrl, String method, String path,
                                            String sourceFile, int sourceLine, String framework) {
        ExtractedEndpoint ep = new ExtractedEndpoint();
        ep.setRepoUrl(repoUrl);
        ep.setHttpMethod(method);
        ep.setPath(normalizePath(path));
        ep.setSourceFile(sourceFile);
        ep.setSourceLine(sourceLine);
        ep.setFramework(framework);
        ep.setParameters(extractPathParams(path));
        return ep;
    }

    /**
     * 规范化路径：确保以 / 开头，去除多余斜杠
     */
    private String normalizePath(String path) {
        if (path == null || path.isEmpty()) return "/";
        path = path.replaceAll("/+", "/");
        if (!path.startsWith("/")) path = "/" + path;
        return path;
    }

    /**
     * 提取路径中的参数名列表（如 :id, {id}）
     */
    private List<String> extractPathParams(String path) {
        List<String> params = new ArrayList<>();
        // Express/Gin 风格 :param
        Matcher colonMatcher = Pattern.compile(":(\\w+)").matcher(path);
        while (colonMatcher.find()) {
            params.add(colonMatcher.group(1));
        }
        // Spring 风格 {param}
        Matcher braceMatcher = Pattern.compile("\\{(\\w+)}").matcher(path);
        while (braceMatcher.find()) {
            params.add(braceMatcher.group(1));
        }
        return params;
    }

    private String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot) : "";
    }

    private void deleteDirectory(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("清理临时目录失败: {}", dir, e);
        }
    }

    /**
     * 提取到的端点数据
     */
    @Data
    public static class ExtractedEndpoint {
        private String repoUrl;
        private String httpMethod;
        private String path;
        private String sourceFile;
        private int sourceLine;
        private String framework;
        private List<String> parameters;
    }
}
