package com.commerce.rag.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.xml.sax.ContentHandler;

/**
 * TikaImageExtractor 单元测试 —— 内嵌图片捕获
 *
 * @author commerce-rag
 */
class TikaImageExtractorTest {

    @Test
    @DisplayName("shouldParseEmbedded — 仅 image/* 返回 true")
    void shouldParseEmbedded_onlyImages() {
        TikaImageExtractor extractor = new TikaImageExtractor();
        Metadata imageMeta = new Metadata();
        imageMeta.set(Metadata.CONTENT_TYPE, "image/png");
        Metadata docMeta = new Metadata();
        docMeta.set(Metadata.CONTENT_TYPE, "application/pdf");

        assertTrue(extractor.shouldParseEmbedded(imageMeta));
        assertFalse(extractor.shouldParseEmbedded(docMeta));
        assertFalse(extractor.shouldParseEmbedded(new Metadata()));
    }

    @Test
    @DisplayName("parseEmbedded — 按资源名捕获字节与 MIME")
    void parseEmbedded_capturesByResourceName() throws Exception {
        TikaImageExtractor extractor = new TikaImageExtractor();
        Metadata metadata = new Metadata();
        metadata.set(Metadata.CONTENT_TYPE, "image/jpeg");
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, "image0.jpg");
        byte[] bytes = "fake-image-bytes".getBytes(StandardCharsets.UTF_8);

        extractor.parseEmbedded(new ByteArrayInputStream(bytes), mock(ContentHandler.class), metadata, false);

        ParsedContent.CapturedImage captured = extractor.getImages().get("image0.jpg");
        assertEquals("image/jpeg", captured.mimeType());
        assertEquals(new String(bytes, StandardCharsets.UTF_8), new String(captured.bytes(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("parseEmbedded — 无资源名时按序号兜底命名")
    void parseEmbedded_fallbackNameByCounter() throws Exception {
        TikaImageExtractor extractor = new TikaImageExtractor();
        extractor.parseEmbedded(
                new ByteArrayInputStream(new byte[] {1}), mock(ContentHandler.class), new Metadata(), false);

        assertTrue(extractor.getImages().containsKey("image0"));
    }
}
