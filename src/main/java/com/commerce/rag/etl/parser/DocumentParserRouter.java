package com.commerce.rag.etl.parser;

import com.commerce.rag.common.exception.RagBusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

/**
 * 文档解析路由器
 *
 * 根据文件扩展名自动路由到对应的 DocumentParser 实现。
 * 收集所有 Spring 容器中注册的 DocumentParser，按 supports() 匹配。
 */
@Slf4j
@Component
public class DocumentParserRouter {

    private final List<DocumentParser> parsers;

    public DocumentParserRouter(List<DocumentParser> parsers) {
        this.parsers = parsers;
        log.info("已注册文档解析器: {}", parsers.stream()
                .map(p -> p.getClass().getSimpleName()).toList());
    }

    /**
     * 根据文件类型路由到对应解析器并执行解析
     *
     * @param inputStream 文档输入流
     * @param fileName    文件名（含扩展名）
     * @return 解析结果
     * @throws RagBusinessException 无匹配解析器时抛出
     */
    public DocumentParser.ParsedDocument parse(InputStream inputStream, String fileName) {
        String fileType = extractFileType(fileName);

        DocumentParser matched = parsers.stream()
                .filter(p -> p.supports(fileType))
                .findFirst()
                .orElseThrow(() -> new RagBusinessException(400,
                        "不支持的文件类型: " + fileType + "，支持的类型: pdf, docx, pptx, md, txt"));

        log.info("路由到解析器: {} 处理文件: {}", matched.getClass().getSimpleName(), fileName);
        return matched.parse(inputStream, fileName);
    }

    /**
     * 从文件名中提取扩展名（不含点号）
     */
    private String extractFileType(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            throw new RagBusinessException(400, "文件名缺少扩展名: " + fileName);
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }
}
