/**
 * @author HXN
 * @date 2026-09-15
 * @description LLM 测试用例生成器（按类分组生成 JUnit5+Mockito 测试类）
 */
package com.platform.whitebox.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.knowledge.pipeline.LlmClient;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 测试用例生成器
 *
 * <p>每个变更类一次 LLM 调用（同类多个变更方法合并为一个测试类），
 * 输入方法源码、context_json（分支结构/Javadoc/依赖）、关联需求条目全文、
 * 类字段清单与 JUnit5 + Mockito 技术约束，要求输出 JSON：</p>
 * <pre>
 * { "testClassCode": "完整测试类源码",
 *   "testCases": [{ "title":"...","caseType":"NORMAL|EXCEPTION","priority":"高|中|低",
 *     "preconditions":"...","steps":["..."],"expectedResult":"...",
 *     "relatedRequirementIds":[1],"testMethodName":"testXxx_normal","sourceMethod":"被测方法名" }] }
 * </pre>
 */
@Slf4j
@Component
public class TestCaseGenerator {

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public TestCaseGenerator(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成结果
     */
    @Data
    public static class GenerateResult {
        /** 完整测试类源码 */
        private String testClassCode;
        /** 测试用例清单 */
        private List<GeneratedCase> testCases = new ArrayList<>();
        /** 消耗 token 数 */
        private int tokensUsed;
    }

    /**
     * 生成的单个测试用例
     */
    @Data
    public static class GeneratedCase {
        private String title;
        private String caseType;
        private String priority;
        private String preconditions;
        private List<String> steps;
        private String expectedResult;
        private List<Long> relatedRequirementIds;
        private String testMethodName;
        /** 来源被测方法名（引擎回填 methodId 用） */
        private String sourceMethod;
    }

    /**
     * 待生成的方法输入（引擎按类分组后传入）
     */
    @Data
    public static class MethodSpec {
        private String methodName;
        private String methodSource;
        private String contextJson;
        private List<Map<String, Object>> relatedItems;
    }

    /**
     * 为单个变更类生成测试类与用例
     *
     * @param className   完整类名（含包名）
     * @param fileContent 类完整源码（类字段/依赖参考）
     * @param methods     该类的变更方法清单
     * @return 生成结果（调用失败时抛出 RuntimeException）
     */
    public GenerateResult generate(String className, String fileContent, List<MethodSpec> methods) {
        try {
            String systemPrompt = "你是资深 Java 白盒测试工程师。基于变更方法源码生成 JUnit 5 + Mockito 单元测试。" +
                    "技术约束：\n" +
                    "1. JUnit 5（org.junit.jupiter.api.*）+ Mockito（org.mockito.*）\n" +
                    "2. 测试类名格式：{ClassName}WhiteboxTest（ClassName 为被测类简单名）\n" +
                    "3. 每个测试方法使用 @Test + @DisplayName(中文描述)，依赖用 @Mock/@InjectMocks\n" +
                    "4. 覆盖正常分支、边界条件、异常分支（每个变更方法 1-4 个用例）\n" +
                    "5. 断言使用 org.junit.jupiter.api.Assertions，异常用例用 assertThrows\n" +
                    "6. 仅输出 JSON，格式：{\"testClassCode\":\"完整测试类源码\",\"testCases\":[{\"title\":\"...\",\"caseType\":\"NORMAL或EXCEPTION\",\"priority\":\"高或中或低\",\"preconditions\":\"...\",\"steps\":[\"...\"],\"expectedResult\":\"...\",\"relatedRequirementIds\":[],\"testMethodName\":\"testXxx\",\"sourceMethod\":\"被测方法名\"}]}";

            StringBuilder userPrompt = new StringBuilder();
            userPrompt.append("## 被测类\n").append(className).append("\n\n");
            if (fileContent != null && !fileContent.isEmpty()) {
                String brief = fileContent.length() > 4000 ? fileContent.substring(0, 4000) : fileContent;
                userPrompt.append("## 类完整源码（含字段与依赖，供 Mock 参考）\n```java\n")
                        .append(brief).append("\n```\n\n");
            }
            for (MethodSpec spec : methods) {
                userPrompt.append("## 变更方法: ").append(spec.getMethodName()).append("\n```java\n")
                        .append(spec.getMethodSource() != null ? spec.getMethodSource() : "")
                        .append("\n```\n");
                if (spec.getContextJson() != null && !spec.getContextJson().isEmpty()) {
                    userPrompt.append("静态分析上下文（分支结构/依赖调用）:\n")
                            .append(spec.getContextJson()).append('\n');
                }
                if (spec.getRelatedItems() != null && !spec.getRelatedItems().isEmpty()) {
                    userPrompt.append("关联需求条目:\n");
                    for (Map<String, Object> item : spec.getRelatedItems()) {
                        userPrompt.append("- 需求[").append(item.get("id")).append("] ")
                                .append(item.getOrDefault("title", "")).append(": ")
                                .append(item.getOrDefault("description", "")).append('\n');
                    }
                }
                userPrompt.append('\n');
            }
            userPrompt.append("请为以上全部变更方法生成一个测试类与用例清单（每个用例标注 sourceMethod），输出 JSON。");

            LlmClient.ChatResult chatResult = llmClient.chat(
                    Arrays.asList(LlmClient.ChatMessage.system(systemPrompt),
                            LlmClient.ChatMessage.user(userPrompt.toString())),
                    0.2);

            GenerateResult result = parseResponse(chatResult.content);
            result.setTokensUsed(chatResult.tokensUsed);
            return result;
        } catch (Exception e) {
            log.error("LLM 生成测试代码失败: className={}", className, e);
            throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
        }
    }

    /**
     * 解析 LLM 响应（容错 ```json 代码块）
     */
    private GenerateResult parseResponse(String content) {
        String s = stripCodeFence(content);
        try {
            Map<String, Object> raw = objectMapper.readValue(s, new TypeReference<Map<String, Object>>() {});
            GenerateResult result = new GenerateResult();
            result.setTestClassCode(String.valueOf(raw.getOrDefault("testClassCode", "")));

            Object casesObj = raw.get("testCases");
            if (casesObj instanceof List) {
                List<GeneratedCase> cases = new ArrayList<>();
                for (Object item : (List<?>) casesObj) {
                    if (item instanceof Map) {
                        Map<?, ?> caseMap = (Map<?, ?>) item;
                        GeneratedCase gc = new GeneratedCase();
                        gc.setTitle(str(caseMap.get("title")));
                        gc.setCaseType(strOr(caseMap.get("caseType"), "NORMAL"));
                        gc.setPriority(strOr(caseMap.get("priority"), "中"));
                        gc.setPreconditions(str(caseMap.get("preconditions")));
                        gc.setExpectedResult(str(caseMap.get("expectedResult")));
                        gc.setTestMethodName(str(caseMap.get("testMethodName")));
                        gc.setSourceMethod(str(caseMap.get("sourceMethod")));
                        Object stepsObj = caseMap.get("steps");
                        if (stepsObj instanceof List) {
                            List<String> steps = new ArrayList<>();
                            for (Object step : (List<?>) stepsObj) {
                                steps.add(String.valueOf(step));
                            }
                            gc.setSteps(steps);
                        }
                        Object reqIdsObj = caseMap.get("relatedRequirementIds");
                        if (reqIdsObj instanceof List) {
                            List<Long> reqIds = new ArrayList<>();
                            for (Object reqId : (List<?>) reqIdsObj) {
                                try {
                                    reqIds.add(Long.parseLong(String.valueOf(reqId)));
                                } catch (NumberFormatException ignored) {
                                }
                            }
                            gc.setRelatedRequirementIds(reqIds);
                        }
                        cases.add(gc);
                    }
                }
                result.setTestCases(cases);
            }
            return result;
        } catch (Exception e) {
            log.warn("解析 LLM 生成结果失败，降级为空结果: {}", e.getMessage());
            return new GenerateResult();
        }
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String strOr(Object value, String defaultValue) {
        return value == null || String.valueOf(value).isEmpty() ? defaultValue : String.valueOf(value);
    }

    /**
     * 去除 ```json 代码块围栏并截取 JSON 主体
     */
    private String stripCodeFence(String content) {
        if (content == null) {
            return "{}";
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
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            s = s.substring(start, end + 1);
        }
        return s;
    }
}
