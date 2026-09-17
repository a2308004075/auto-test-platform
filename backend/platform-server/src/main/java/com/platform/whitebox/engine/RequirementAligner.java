/**
 * @author HXN
 * @date 2026-09-15
 * @description 需求条目对齐器（LLM 关联变更方法与需求）
 */
package com.platform.whitebox.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.knowledge.pipeline.LlmClient;
import com.platform.whitebox.entity.AiWhiteboxMethod;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 需求条目对齐器
 *
 * <p>一次 LLM 调用：输入变更方法清单（签名+摘要）与需求条目（id/title/description），
 * 输出 JSON {@code [{methodIndex, requirementIds[]}]}，回写 method.related_requirements_json。</p>
 */
@Slf4j
@Component
public class RequirementAligner {

    /** 需求描述截断长度（token 保护） */
    private static final int DESCRIPTION_MAX_LENGTH = 500;

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public RequirementAligner(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 对齐结果
     */
    public static class AlignResult {
        /** 消耗 token 数 */
        public int tokensUsed;
    }

    /**
     * 执行需求对齐（直接回写 method.relatedRequirementsJson）
     *
     * @param methods 变更方法列表（含 sourceCode）
     * @param items   需求条目列表（RequirementItem 实体映射的轻量 Map：id/title/description）
     * @return 对齐结果；无需求条目或调用失败时方法不标记关联
     */
    public AlignResult align(List<AiWhiteboxMethod> methods, List<Map<String, Object>> items) {
        AlignResult result = new AlignResult();
        if (items == null || items.isEmpty()) {
            log.info("无需求条目可对齐，跳过需求对齐阶段");
            return result;
        }
        if (methods == null || methods.isEmpty()) {
            return result;
        }

        try {
            // 构建提示词
            StringBuilder methodSection = new StringBuilder();
            for (int i = 0; i < methods.size(); i++) {
                AiWhiteboxMethod m = methods.get(i);
                String brief = m.getSourceCode() != null && m.getSourceCode().length() > 300
                        ? m.getSourceCode().substring(0, 300) : m.getSourceCode();
                methodSection.append("方法[").append(i).append("] ")
                        .append(m.getClassName()).append(" # ")
                        .append(m.getMethodSignature() != null ? m.getMethodSignature() : m.getMethodName())
                        .append("\n").append(brief).append("\n\n");
            }

            StringBuilder requirementSection = new StringBuilder();
            for (Map<String, Object> item : items) {
                String desc = String.valueOf(item.getOrDefault("description", ""));
                if (desc.length() > DESCRIPTION_MAX_LENGTH) {
                    desc = desc.substring(0, DESCRIPTION_MAX_LENGTH);
                }
                requirementSection.append("需求[").append(item.get("id")).append("] ")
                        .append(item.getOrDefault("title", ""))
                        .append(": ").append(desc).append("\n");
            }

            String systemPrompt = "你是软件测试需求分析专家。将代码变更方法与需求条目进行语义关联。" +
                    "仅输出 JSON 数组，格式: [{\"methodIndex\":0,\"requirementIds\":[1,2]}]，" +
                    "methodIndex 为方法序号，requirementIds 为关联的需求条目 ID 数组（无关联则返回空数组）。" +
                    "不要输出任何其他文字。";

            String userPrompt = "## 代码变更方法清单\n" + methodSection +
                    "\n## 需求条目清单\n" + requirementSection +
                    "\n请输出方法与需求的关联 JSON 数组。";

            LlmClient.ChatResult chatResult = llmClient.chat(
                    Arrays.asList(LlmClient.ChatMessage.system(systemPrompt),
                            LlmClient.ChatMessage.user(userPrompt)),
                    0.1);
            result.tokensUsed += chatResult.tokensUsed;

            // 解析响应（容错 ```json 代码块）
            String content = stripCodeFence(chatResult.content);
            List<AlignItem> alignItems = objectMapper.readValue(content, new TypeReference<List<AlignItem>>() {});

            for (AlignItem item : alignItems) {
                if (item.methodIndex == null || item.methodIndex < 0 || item.methodIndex >= methods.size()) {
                    continue;
                }
                AiWhiteboxMethod method = methods.get(item.methodIndex);
                if (item.requirementIds != null && !item.requirementIds.isEmpty()) {
                    method.setRelatedRequirementsJson(objectMapper.writeValueAsString(item.requirementIds));
                }
            }
            log.info("需求对齐完成: {} 个方法, {} 条需求", methods.size(), items.size());
        } catch (Exception e) {
            // 对齐失败不阻塞任务，仅记录日志
            log.warn("需求对齐失败（不阻塞任务）: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 去除 ```json 代码块围栏
     */
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
        // 截取首个 [ 到末个 ] 之间内容（容错模型输出前后缀文字）
        int start = s.indexOf('[');
        int end = s.lastIndexOf(']');
        if (start >= 0 && end > start) {
            s = s.substring(start, end + 1);
        }
        return s;
    }

    /** LLM 输出条目 */
    public static class AlignItem {
        public Integer methodIndex;
        public List<Long> requirementIds;
    }
}
