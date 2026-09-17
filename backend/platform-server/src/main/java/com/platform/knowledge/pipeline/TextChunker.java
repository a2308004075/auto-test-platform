/**
 * @author HXN
 * @date 2026-09-15
 * @description 文本分块器
 */
package com.platform.knowledge.pipeline;

import com.platform.knowledge.config.KnowledgeConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 递归字符文本分块器
 *
 * <p>将长文本按分隔符层级（双换行 → 单换行 → 句号 → 空格 → 字符）递归分割，
 * 每块不超过 chunkSize 个 token（近似字符数 / 4），相邻块有 chunkOverlap 重叠。</p>
 */
@Slf4j
@Component
public class TextChunker {

    private final KnowledgeConfig config;

    /** 分隔符优先级：双换行 > 单换行 > 句号 > 空格 > 字符 */
    private static final String[] SEPARATORS = {"\n\n", "\n", "。", ".", " ", ""};

    /** 粗略 token 估算：1 token ≈ 4 字符（中英文混合取保守值） */
    private static final int CHARS_PER_TOKEN = 4;

    public TextChunker(KnowledgeConfig config) {
        this.config = config;
    }

    /**
     * 将文本分割为多个块
     *
     * @param text 完整文本
     * @return 分块列表
     */
    public List<ChunkResult> split(String text) {
        int maxChars = config.getChunking().getChunkSize() * CHARS_PER_TOKEN;
        int overlapChars = config.getChunking().getChunkOverlap() * CHARS_PER_TOKEN;

        List<String> chunks = recursiveSplit(text, maxChars, SEPARATORS, 0);

        // 应用重叠窗口
        List<ChunkResult> results = new ArrayList<>();
        String previousTail = "";
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            // 非首块添加前一块尾部作为重叠
            if (i > 0 && !previousTail.isEmpty()) {
                chunk = previousTail + chunk;
            }
            // 截取到 maxChars
            if (chunk.length() > maxChars) {
                chunk = chunk.substring(0, maxChars);
            }
            // 记录当前块尾部（用于下一块重叠）
            if (chunk.length() > overlapChars) {
                previousTail = chunk.substring(chunk.length() - overlapChars);
            } else {
                previousTail = chunk;
            }

            ChunkResult result = new ChunkResult();
            result.index = i;
            result.content = chunk.trim();
            result.tokenCount = Math.max(1, chunk.length() / CHARS_PER_TOKEN);
            results.add(result);
        }

        log.debug("文本分块完成: {} 字符 -> {} 块 (maxChars={}, overlap={})",
                text.length(), results.size(), maxChars, overlapChars);
        return results;
    }

    /**
     * 递归分割文本
     */
    private List<String> recursiveSplit(String text, int maxChars, String[] separators, int sepIndex) {
        if (text.length() <= maxChars) {
            List<String> result = new ArrayList<>();
            if (!text.trim().isEmpty()) {
                result.add(text.trim());
            }
            return result;
        }

        if (sepIndex >= separators.length) {
            // 所有分隔符都试过了，按字符强制切割
            return forceSplit(text, maxChars);
        }

        String separator = separators[sepIndex];
        String[] parts;
        if (separator.isEmpty()) {
            // 空分隔符 = 按字符切
            return forceSplit(text, maxChars);
        }

        parts = text.split(java.util.regex.Pattern.quote(separator));

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String part : parts) {
            if (current.length() + separator.length() + part.length() <= maxChars) {
                if (current.length() > 0) {
                    current.append(separator);
                }
                current.append(part);
            } else {
                // 当前累积块已满
                if (current.length() > 0) {
                    chunks.add(current.toString().trim());
                }
                // 如果单个 part 超过 maxChars，递归用下一级分隔符
                if (part.length() > maxChars) {
                    chunks.addAll(recursiveSplit(part, maxChars, separators, sepIndex + 1));
                    current = new StringBuilder();
                } else {
                    current = new StringBuilder(part);
                }
            }
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }

        return chunks;
    }

    /**
     * 按字符强制切割（最后手段）
     */
    private List<String> forceSplit(String text, int maxChars) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < text.length(); i += maxChars) {
            int end = Math.min(i + maxChars, text.length());
            String chunk = text.substring(i, end).trim();
            if (!chunk.isEmpty()) {
                result.add(chunk);
            }
        }
        return result;
    }

    /**
     * 分块结果
     */
    public static class ChunkResult {
        public int index;
        public String content;
        public int tokenCount;
    }
}
