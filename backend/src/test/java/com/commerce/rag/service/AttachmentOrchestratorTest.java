package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.record.AttachmentContext;
import com.commerce.rag.record.AttachmentRecord;
import com.commerce.rag.record.DocumentLocalChunk;
import com.commerce.rag.record.ImageCaptionResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AttachmentOrchestrator 单元测试 —— 下载 → 按类型分发（图片 caption / 文档局部语料）→ 组装 AttachmentContext
 *
 * <p>核心验证：图片字节交 imageProcessor、文档字节交 documentProcessor、单项失败跳过不中断、
 * 空/全失败附件返回 empty（spec §5.1 单项失败不阻断对话）。
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AttachmentOrchestrator 附件编排测试")
class AttachmentOrchestratorTest {

    @Mock
    private IAttachmentService attachmentService;

    @Mock
    private AttachmentImageProcessor imageProcessor;

    @Mock
    private AttachmentDocumentProcessor documentProcessor;

    private AttachmentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new AttachmentOrchestrator(attachmentService, imageProcessor, documentProcessor);
    }

    @Test
    @DisplayName("图片附件 — 下载字节后交 imageProcessor，captions 组装入 context")
    void process_imageAttachment_delegatesToImageProcessor() {
        // Given: 单张图片附件，download 返回字节，imageProcessor 产出 caption
        byte[] bytes = new byte[] {1, 2, 3};
        when(attachmentService.download("0/a.png")).thenReturn(bytes);
        when(imageProcessor.processImages(List.of(bytes), List.of("a.png")))
                .thenReturn(List.of(new ImageCaptionResult("图片1:红色图表", "a.png")));

        // When
        AttachmentContext ctx = orchestrator.process(List.of(new AttachmentRecord("image", "0/a.png", "a.png", 1L)));

        // Then: 图片字节交给 imageProcessor，不触发文档处理；captions 组装、documents 为空
        verify(attachmentService).download("0/a.png");
        verify(imageProcessor).processImages(List.of(bytes), List.of("a.png"));
        verify(documentProcessor, never()).processDocument(any(), any());
        assertEquals(1, ctx.captions().size());
        assertEquals("图片1:红色图表", ctx.captions().get(0).caption());
        assertTrue(ctx.documents().isEmpty());
        assertTrue(ctx.hasAny());
    }

    @Test
    @DisplayName("文档附件 — 下载字节后交 documentProcessor，documents 按 url 键组装")
    void process_documentAttachment_delegatesToDocumentProcessor() {
        // Given: 单个文档附件，download 返回字节，documentProcessor 产出局部分片
        byte[] bytes = new byte[] {4, 5, 6};
        when(attachmentService.download("0/doc.pdf")).thenReturn(bytes);
        List<DocumentLocalChunk> chunks = List.of(new DocumentLocalChunk("附件正文", new float[] {1f}, 0));
        when(documentProcessor.processDocument(bytes, "doc.pdf")).thenReturn(chunks);

        // When
        AttachmentContext ctx =
                orchestrator.process(List.of(new AttachmentRecord("document", "0/doc.pdf", "doc.pdf", 2L)));

        // Then: 文档字节交给 documentProcessor，不触发图片处理；documents 以 url 为键
        verify(documentProcessor).processDocument(bytes, "doc.pdf");
        verify(imageProcessor, never()).processImages(anyList(), anyList());
        assertTrue(ctx.documents().containsKey("0/doc.pdf"));
        assertEquals(chunks, ctx.documents().get("0/doc.pdf"));
        assertTrue(ctx.captions().isEmpty());
        assertTrue(ctx.hasAny());
    }

    @Test
    @DisplayName("混合附件 — 图片与文档各自进入对应处理器并组装完整 context")
    void process_mixedAttachments_bothProcessorsInvoked() {
        // Given: 1 图 + 1 文档，均处理成功
        byte[] imgBytes = new byte[] {1};
        byte[] docBytes = new byte[] {2};
        when(attachmentService.download("0/a.png")).thenReturn(imgBytes);
        when(attachmentService.download("0/doc.pdf")).thenReturn(docBytes);
        when(imageProcessor.processImages(List.of(imgBytes), List.of("a.png")))
                .thenReturn(List.of(new ImageCaptionResult("图片1:柱状图", "a.png")));
        List<DocumentLocalChunk> chunks = List.of(new DocumentLocalChunk("课程大纲", new float[] {1f}, 0));
        when(documentProcessor.processDocument(docBytes, "doc.pdf")).thenReturn(chunks);

        // When
        AttachmentContext ctx = orchestrator.process(List.of(
                new AttachmentRecord("image", "0/a.png", "a.png", 1L),
                new AttachmentRecord("document", "0/doc.pdf", "doc.pdf", 2L)));

        // Then: 两个处理器各执行一次，context 两边齐全
        verify(imageProcessor).processImages(List.of(imgBytes), List.of("a.png"));
        verify(documentProcessor).processDocument(docBytes, "doc.pdf");
        assertEquals(1, ctx.captions().size());
        assertTrue(ctx.documents().containsKey("0/doc.pdf"));
    }

    @Test
    @DisplayName("单项失败 — 跳过失败附件，其余附件正常处理（不中断）")
    void process_partialFailure_skipsFailedAndProcessesOthers() {
        // Given: 第一张图正常、第二张图下载抛异常
        byte[] good = new byte[] {7};
        when(attachmentService.download("0/good.png")).thenReturn(good);
        when(attachmentService.download("0/bad.png")).thenThrow(new RuntimeException("MinIO 不可用"));
        when(imageProcessor.processImages(List.of(good), List.of("good.png")))
                .thenReturn(List.of(new ImageCaptionResult("图片1:正常图", "good.png")));

        // When
        AttachmentContext ctx = orchestrator.process(List.of(
                new AttachmentRecord("image", "0/good.png", "good.png", 1L),
                new AttachmentRecord("image", "0/bad.png", "bad.png", 1L)));

        // Then: 失败项跳过（不抛异常），正常图片仍完成 caption
        assertEquals(1, ctx.captions().size());
        assertEquals("图片1:正常图", ctx.captions().get(0).caption());
        assertTrue(ctx.hasAny());
    }

    @Test
    @DisplayName("全部失败 — 返回 empty，不中断调用方")
    void process_allFailures_returnsEmpty() {
        // Given: 所有附件下载失败
        when(attachmentService.download(anyString())).thenThrow(new RuntimeException("MinIO 不可用"));

        // When
        AttachmentContext ctx = orchestrator.process(List.of(
                new AttachmentRecord("image", "0/a.png", "a.png", 1L),
                new AttachmentRecord("document", "0/b.pdf", "b.pdf", 2L)));

        // Then: 无任何 captions/documents，处理器均不触发
        assertFalse(ctx.hasAny());
        assertTrue(ctx.captions().isEmpty());
        assertTrue(ctx.documents().isEmpty());
        verify(imageProcessor, never()).processImages(anyList(), anyList());
        verify(documentProcessor, never()).processDocument(any(), any());
    }

    @Test
    @DisplayName("空附件列表 — 直接返回 empty，不访问服务与处理器")
    void process_emptyAttachments_returnsEmpty() {
        // When
        AttachmentContext ctx = orchestrator.process(List.of());

        // Then: empty 且下游零交互（无多余下载/处理）
        assertFalse(ctx.hasAny());
        assertTrue(ctx.captions().isEmpty());
        assertTrue(ctx.documents().isEmpty());
        verifyNoInteractions(attachmentService, imageProcessor, documentProcessor);
    }

    @Test
    @DisplayName("null 附件列表 — 返回 empty，不抛异常")
    void process_nullAttachments_returnsEmpty() {
        // When
        AttachmentContext ctx = orchestrator.process(null);

        // Then: null 入参按空处理
        assertFalse(ctx.hasAny());
        verifyNoInteractions(attachmentService, imageProcessor, documentProcessor);
    }

    @Test
    @DisplayName("AttachmentContext.empty — hasAny 恒为 false，两栏均为空")
    void emptyContext_hasAnyFalse() {
        // When
        AttachmentContext ctx = AttachmentContext.empty();

        // Then: 无附件上下文
        assertFalse(ctx.hasAny());
        assertTrue(ctx.captions().isEmpty());
        assertTrue(ctx.documents().isEmpty());
    }

    @Test
    @DisplayName("AttachmentContext.hasAny — 仅含 captions 或仅含 documents 均判定有附件上下文")
    void hasAny_returnsTrueWhenEitherPresent() {
        // When: 仅 captions
        AttachmentContext onlyCaptions =
                new AttachmentContext(List.of(new ImageCaptionResult("图片1:图", "a.png")), Map.of());
        assertTrue(onlyCaptions.hasAny());

        // When: 仅 documents
        AttachmentContext onlyDocs = new AttachmentContext(
                List.of(), Map.of("0/a.pdf", List.of(new DocumentLocalChunk("内容", new float[] {1f}, 0))));
        assertTrue(onlyDocs.hasAny());
    }
}
