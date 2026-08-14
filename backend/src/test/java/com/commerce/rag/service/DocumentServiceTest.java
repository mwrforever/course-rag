package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.entity.Document;
import com.commerce.rag.entity.KnowledgeBase;
import com.commerce.rag.etl.EtlPipeline;
import com.commerce.rag.mapper.DocumentChunkMapper;
import com.commerce.rag.mapper.DocumentMapper;
import com.commerce.rag.mapper.KnowledgeBaseMapper;
import com.commerce.rag.storage.MinioStorageService;
import com.commerce.rag.test.MybatisPlusTestHelper;
import java.io.ByteArrayInputStream;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/**
 * DocumentService 权限校验单元测试 —— 改名/下载/上传的归属校验（P0-2a/b/c）
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentService 文档权限测试")
class DocumentServiceTest {

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private DocumentChunkMapper chunkMapper;

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Mock
    private MinioStorageService minioStorageService;

    @Mock
    private EtlPipeline etlPipeline;

    @Mock
    private ThreadPoolExecutor etlPool;

    private DocumentService documentService;

    @BeforeAll
    static void initMybatisPlus() {
        MybatisPlusTestHelper.initTableInfo();
    }

    @BeforeEach
    void setUp() throws Exception {
        documentService = new DocumentService();
        // 字段为 @Autowired 私有字段，通过反射注入 mock
        for (java.lang.reflect.Field f : DocumentService.class.getDeclaredFields()) {
            f.setAccessible(true);
            Object value =
                    switch (f.getName()) {
                        case "documentMapper" -> documentMapper;
                        case "chunkMapper" -> chunkMapper;
                        case "knowledgeBaseMapper" -> knowledgeBaseMapper;
                        case "minioStorageService" -> minioStorageService;
                        case "etlPipeline" -> etlPipeline;
                        case "etlPool" -> etlPool;
                        default -> null;
                    };
            if (value != null) {
                f.set(documentService, value);
            }
        }
    }

    private Document mockDoc(Long id, Long createdBy) {
        Document doc = new Document();
        doc.setId(id);
        doc.setCreatedBy(createdBy);
        doc.setSourcePath("kb/1/doc.pdf");
        return doc;
    }

    @Test
    @DisplayName("update → 非创建者改名抛出 403（超管旁路）")
    void update_notOwner_throws403() {
        when(documentMapper.selectById(1L)).thenReturn(mockDoc(1L, 100L));

        // 操作者 200 不是创建者 100 → 403
        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> documentService.update(1L, "新标题", 200L, false));
        assertEquals(403, ex.getStatusCode().value());
        // 403 路径零写入副作用：不得触发任何 DB 更新
        verify(documentMapper, never()).update(any(), any());
        // 超管旁路：不抛异常
        assertDoesNotThrow(() -> documentService.update(1L, "新标题", 200L, true));
    }

    @Test
    @DisplayName("download → 非创建者下载抛出 403")
    void download_notOwner_throws403() {
        when(documentMapper.selectById(1L)).thenReturn(mockDoc(1L, 100L));

        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> documentService.download(1L, 200L, false));
        assertEquals(403, ex.getStatusCode().value());
        // 403 路径零副作用：不得从 MinIO 读取文件
        verify(minioStorageService, never()).downloadFile(anyString());
    }

    @Test
    @DisplayName("upload → 非超管向他人知识库上传抛出 403")
    void upload_notKbOwner_throws403() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setCreatedBy(100L);
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);

        // 用户 200 向 createdBy=100 的知识库上传 → 403
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> documentService.upload(
                        1L, "doc", new ByteArrayInputStream(new byte[0]), "pdf", 10L, 200L, false));
        assertEquals(403, ex.getStatusCode().value());
        // 403 路径零副作用：documentMapper 零交互（未落库）
        verifyNoInteractions(documentMapper);
    }

    @Test
    @DisplayName("upload → 知识库创建者可正常上传")
    void upload_kbOwner_succeeds() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setCreatedBy(100L);
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);

        assertDoesNotThrow(() ->
                documentService.upload(1L, "doc", new ByteArrayInputStream(new byte[0]), "pdf", 10L, 100L, false));
        verify(documentMapper).insert(any(Document.class));
    }
}
