package com.commerce.rag.etl;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

/**
 * Tika 内嵌图片捕获器 —— EmbeddedDocumentExtractor 实现
 *
 * <p>PDF 内联图片与 OOXML（DOCX/PPTX）内嵌图片经 Tika 容器解析器路由到本提取器，
 * 捕获图片字节与 MIME 类型，以资源名（resourceName）为键，供 XhtmlDocumentParser 按
 * XHTML 中的 &lt;img src="embedded:xxx"&gt; 定位。
 *
 * @author commerce-rag
 */
public class TikaImageExtractor implements EmbeddedDocumentExtractor {

    private final Map<String, ParsedContent.CapturedImage> images = new LinkedHashMap<>();

    /** 无资源名图片的捕获序号（兜底命名） */
    private final AtomicInteger counter = new AtomicInteger();

    /**
     * 是否解析该内嵌资源 —— 仅图片（image/*）需要捕获，其余内嵌文档跳过
     */
    @Override
    public boolean shouldParseEmbedded(Metadata metadata) {
        return metadata.get(Metadata.CONTENT_TYPE) != null
                && metadata.get(Metadata.CONTENT_TYPE).startsWith("image/");
    }

    /**
     * 捕获内嵌图片字节与 MIME
     */
    @Override
    public void parseEmbedded(InputStream stream, ContentHandler handler, Metadata metadata, boolean outputHtml)
            throws SAXException, IOException {
        byte[] bytes = stream.readAllBytes();
        if (bytes.length == 0) {
            return;
        }
        String resourceName = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY);
        if (resourceName == null || resourceName.isBlank()) {
            resourceName = "image" + counter.getAndIncrement();
        }
        images.putIfAbsent(resourceName, new ParsedContent.CapturedImage(bytes, metadata.get(Metadata.CONTENT_TYPE)));
    }

    /**
     * @return 捕获到的图片映射（resourceName → 字节与 MIME）
     */
    public Map<String, ParsedContent.CapturedImage> getImages() {
        return images;
    }
}
