/**
 * @author HXN
 * @date 2026-09-15
 * @description Java 方法静态分析器（JavaParser AST 提取上下文包）
 */
package com.platform.whitebox.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java 方法静态分析器
 *
 * <p>基于 JavaParser AST 为每个变更方法生成 context_json 上下文包：
 * <ul>
 *   <li>方法签名与修饰符</li>
 *   <li>控制流摘要（if/else if/switch-case/try-catch/循环计数 + 嵌套描述）</li>
 *   <li>Javadoc 注释</li>
 *   <li>方法体内外部调用清单（Mock 决策提示）</li>
 *   <li>所属类字段清单（Mock 目标提示）</li>
 * </ul>
 */
@Slf4j
@Component
public class JavaMethodAnalyzer {

    private final ObjectMapper objectMapper;

    public JavaMethodAnalyzer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 分析方法生成上下文 JSON
     *
     * @param sourceCode  方法源码（含方法声明）
     * @param fileContent 所属文件完整内容（用于类字段提取）
     * @param methodName  目标方法名
     * @return context_json 字符串；解析失败返回 null
     */
    public String analyze(String sourceCode, String fileContent, String methodName) {
        try {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("methodName", methodName);

            // 解析整个文件获取类字段
            String parseTarget = (fileContent != null && !fileContent.isEmpty()) ? fileContent : sourceCode;
            JavaParser parser = new JavaParser();
            CompilationUnit cu = parser.parse(parseTarget).getResult().orElse(null);
            if (cu == null) {
                log.warn("JavaParser 解析失败: methodName={}", methodName);
                return null;
            }

            // 类字段清单（Mock 目标提示）
            List<Map<String, String>> fields = new ArrayList<>();
            List<String> imports = new ArrayList<>();
            cu.getImports().forEach(i -> imports.add(i.getNameAsString()));
            ClassOrInterfaceDeclaration clazz = cu.findFirst(ClassOrInterfaceDeclaration.class).orElse(null);
            if (clazz != null) {
                context.put("className", clazz.getNameAsString());
                for (FieldDeclaration field : clazz.getFields()) {
                    for (VariableDeclarator var : field.getVariables()) {
                        Map<String, String> f = new LinkedHashMap<>();
                        f.put("name", var.getNameAsString());
                        f.put("type", var.getTypeAsString());
                        String modifier = field.getModifiers().toString();
                        f.put("mockable", modifier.contains("private") || modifier.contains("protected") ? "yes" : "no");
                        fields.add(f);
                    }
                }
            }
            context.put("classFields", fields);
            context.put("imports", imports);

            // 找到目标方法
            MethodDeclaration target = null;
            if (clazz != null) {
                for (MethodDeclaration md : clazz.getMethods()) {
                    if (md.getNameAsString().equals(methodName)) {
                        target = md;
                        break;
                    }
                }
            }

            if (target != null) {
                // 签名与修饰符
                context.put("signature", target.getDeclarationAsString(true, false, false));
                context.put("modifiers", target.getModifiers().toString());
                context.put("parameters", buildParameters(target));
                context.put("returnType", target.getTypeAsString());

                // Javadoc
                target.getJavadoc().ifPresent(jd -> context.put("javadoc", jd.toText().trim()));

                // 控制流摘要
                context.put("controlFlow", buildControlFlow(target));

                // 外部调用清单（Mock 决策提示）
                List<String> calls = new ArrayList<>();
                target.findAll(MethodCallExpr.class).forEach(call -> {
                    String scope = call.getScope().map(s -> s.toString()).orElse("this");
                    calls.add(scope + "." + call.getNameAsString() + "()");
                });
                context.put("methodCalls", calls);
            }

            return objectMapper.writeValueAsString(context);
        } catch (Exception e) {
            log.warn("方法静态分析失败: methodName={}", methodName, e);
            return null;
        }
    }

    /**
     * 构建参数清单（名称 + 类型）
     */
    private List<Map<String, String>> buildParameters(MethodDeclaration md) {
        List<Map<String, String>> params = new ArrayList<>();
        md.getParameters().forEach(p -> {
            Map<String, String> param = new LinkedHashMap<>();
            param.put("name", p.getNameAsString());
            param.put("type", p.getTypeAsString());
            params.add(param);
        });
        return params;
    }

    /**
     * 构建控制流摘要
     */
    private Map<String, Object> buildControlFlow(MethodDeclaration md) {
        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("ifCount", md.findAll(IfStmt.class).size());
        flow.put("elseIfCount", md.findAll(IfStmt.class).stream()
                .mapToInt(i -> i.getElseStmt().filter(e -> e instanceof IfStmt).map(e -> 1).orElse(0)).sum());
        flow.put("switchCases", md.findAll(SwitchEntry.class).size());
        flow.put("tryCatchBlocks", md.findAll(TryStmt.class).size());
        flow.put("catchTypes", buildCatchTypes(md));
        flow.put("forLoops", md.findAll(ForStmt.class).size());
        flow.put("forEachLoops", md.findAll(ForEachStmt.class).size());
        flow.put("whileLoops", md.findAll(WhileStmt.class).size());
        flow.put("doWhileLoops", md.findAll(DoStmt.class).size());
        flow.put("maxNestingDepth", calcMaxNesting(md));
        flow.put("hasReturn", !md.getBody().map(b -> b.findAll(ReturnStmt.class).isEmpty()).orElse(true));
        return flow;
    }

    /**
     * 收集 catch 的异常类型（异常用例生成提示）
     */
    private List<String> buildCatchTypes(MethodDeclaration md) {
        List<String> types = new ArrayList<>();
        md.findAll(TryStmt.class).forEach(tryStmt ->
                tryStmt.getCatchClauses().forEach(c ->
                        types.add(c.getParameter().getType().asString())));
        return types;
    }

    /**
     * 计算最大嵌套深度（粗略：节点层级遍历）
     */
    private int calcMaxNesting(MethodDeclaration md) {
        int[] max = {0};
        md.getBody().ifPresent(body -> walkDepth(body, 1, max));
        return max[0];
    }

    private void walkDepth(com.github.javaparser.ast.Node node, int depth, int[] max) {
        if (depth > max[0]) {
            max[0] = depth;
        }
        for (com.github.javaparser.ast.Node child : node.getChildNodes()) {
            boolean isBlock = child instanceof IfStmt || child instanceof SwitchStmt
                    || child instanceof TryStmt || child instanceof ForStmt
                    || child instanceof ForEachStmt || child instanceof WhileStmt
                    || child instanceof DoStmt;
            walkDepth(child, isBlock ? depth + 1 : depth, max);
        }
    }
}
