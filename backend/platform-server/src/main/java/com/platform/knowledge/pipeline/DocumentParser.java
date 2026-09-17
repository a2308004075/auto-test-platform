/**
 * @author HXN
 * @date 2026-09-15
 * @description 文档解析器
 */
package com.platform.knowledge.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Component;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 文档内容解析器
 *
 * <p>基于 Apache Tika，将项目资料磁盘文件提取为纯文本。
 * 支持 PDF、Word（.docx）、Excel（.xlsx）、Visio（.vsdx）、TXT、Markdown、HTML 等格式。
 * 对于 Tika 无法有效解析的 .vsdx 文件，提供 ZIP 内嵌 XML 回退提取。</p>
 */
@Slf4j
@Component
public class DocumentParser {

    private final Tika tika = new Tika();

    /**
     * 从磁盘文件提取纯文本内容
     *
     * @param filePath    文件绝对路径
     * @param contentType MIME 类型（可选，用于日志）
     * @return 提取的纯文本
     * @throws IOException 文件读取失败
     */
    public String parse(String filePath, String contentType) throws IOException {
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            throw new IOException("文件不存在或不是有效文件: " + filePath);
        }

        try (InputStream is = new FileInputStream(file)) {
            String text = tika.parseToString(is);
            // .vsdx 文件 Tika 解析效果差，尝试回退解析
            if ((text == null || text.trim().isEmpty()) && isVsdxFile(filePath)) {
                log.info("Tika 解析 .vsdx 为空，启用回退解析: {}", filePath);
                text = parseVsdx(file);
            }
    
            if (text == null || text.trim().isEmpty()) {
                log.warn("文档解析结果为空: {} (contentType={})", filePath, contentType);
                return "";
            }
            log.info("文档解析完成: {} -> {} 字符", filePath, text.length());
            return text.trim();
        } catch (TikaException e) {
            // Tika 异常时也尝试 .vsdx 回退
            if (isVsdxFile(filePath)) {
                log.warn("Tika 解析 .vsdx 异常，启用回退解析: {}", filePath);
                String fallback = parseVsdx(file);
                if (fallback != null && !fallback.trim().isEmpty()) {
                    return fallback.trim();
                }
            }
            log.error("Tika 解析失败: {} (contentType={})", filePath, contentType, e);
            throw new IOException("文档解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 InputStream 提取纯文本（用于直接流处理）
     */
    public String parse(InputStream inputStream) throws IOException {
        try {
            String text = tika.parseToString(inputStream);
            return text != null ? text.trim() : "";
        } catch (TikaException e) {
            throw new IOException("文档解析失败: " + e.getMessage(), e);
        }
    }

    // ===== .vsdx 回退解析 =====

    private boolean isVsdxFile(String filePath) {
        return filePath != null && filePath.toLowerCase().endsWith(".vsdx");
    }

    /**
     * 回退解析 .vsdx 文件：遍历 ZIP 内所有 XML 条目，提取全部文本节点内容。
     *
     * <p>.vsdx 本质是 ZIP 包，内含 visio/pages/page*.xml 等 XML 文件，
     * 图形文本存储在 XML 元素中（如 {@code <a:t>} / {@code <cp>} 等）。
     * 通过遍历所有 XML 条目的文本节点来提取内容。</p>
     */
    private String parseVsdx(File file) {
        StringBuilder sb = new StringBuilder();
        try (ZipFile zip = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().toLowerCase();
                // 只处理 pages 目录下的 XML 文件（包含图形文本）
                if (entry.isDirectory() || !name.endsWith(".xml")) {
                    continue;
                }
                if (!name.contains("pages/page") && !name.contains("masters/master")) {
                    continue;
                }
                try (InputStream xmlIs = zip.getInputStream(entry)) {
                    org.w3c.dom.Document doc = DocumentBuilderFactory.newInstance()
                            .newDocumentBuilder().parse(xmlIs);
                    String textContent = doc.getDocumentElement().getTextContent();
                    if (textContent != null && !textContent.trim().isEmpty()) {
                        sb.append(textContent.trim()).append("\n\n");
                    }
                } catch (Exception e) {
                    log.debug("跳过 .vsdx XML 条目: {} ({})", entry.getName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error(".vsdx 回退解析失败: {}", file.getName(), e);
        }

        String result = sb.toString().trim();
        if (!result.isEmpty()) {
            log.info(".vsdx 回退解析成功: {} -> {} 字符", file.getName(), result.length());
        }
        return result;
    }
}
