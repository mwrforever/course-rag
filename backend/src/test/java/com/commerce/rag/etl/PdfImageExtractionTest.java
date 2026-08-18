package com.commerce.rag.etl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.ToHTMLContentHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 真实 PDF 图片提取集成测试 —— Tika 默认 imageStrategy=NONE 不提取 PDF 图片
 * （字节码实锤），必须显式设置 RAW_IMAGES 才能捕获内嵌图片。
 *
 * <p>fixture: src/test/resources/etl/higher-math-with-image.pdf（reportlab 生成，
 * 含 1 张 JPEG 内嵌图 + 中文文本 + 表格）
 *
 * @author commerce-rag
 */
class PdfImageExtractionTest {

    @Test
    @DisplayName("显式开启 RAW_IMAGES — 真实 PDF 内嵌图片被捕获且 XHTML 含 img 引用")
    void rawImagesStrategy_capturesPdfImages() throws Exception {
        try (InputStream in = PdfImageExtractionTest.class.getResourceAsStream("/etl/higher-math-with-image.pdf")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ToHTMLContentHandler handler = new ToHTMLContentHandler(out, "UTF-8");
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            // 与 EtlPipeline.parseDocument 保持一致的显式开启配置
            PDFParserConfig pdfConfig = new PDFParserConfig();
            pdfConfig.setExtractInlineImages(true);
            pdfConfig.setImageStrategy(PDFParserConfig.IMAGE_STRATEGY.RAW_IMAGES);
            context.set(PDFParserConfig.class, pdfConfig);
            TikaImageExtractor imageExtractor = new TikaImageExtractor();
            context.set(EmbeddedDocumentExtractor.class, imageExtractor);
            AutoDetectParser parser = new AutoDetectParser();
            parser.parse(in, handler, metadata, context);

            // 图片被 TikaImageExtractor 捕获（≥1 张）
            assertFalse(imageExtractor.getImages().isEmpty(), "RAW_IMAGES 下 PDF 内嵌图片应被捕获");
            // XHTML 中包含对捕获图片的引用（embedded: 资源名）
            String xhtml = out.toString(StandardCharsets.UTF_8);
            assertTrue(xhtml.contains("<img"), "XHTML 应包含 <img> 引用");
            // 中文正文可正常提取（验证字体嵌入未乱码）
            assertTrue(xhtml.contains("导数"), "XHTML 应包含中文正文（验证字体嵌入）");
        }
    }
}
