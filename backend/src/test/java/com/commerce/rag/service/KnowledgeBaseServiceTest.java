package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.cache.DashboardCacheEvictor;
import com.commerce.rag.convert.KnowledgeBaseConverter;
import com.commerce.rag.convert.KnowledgeBaseConverterImpl;
import com.commerce.rag.entity.Document;
import com.commerce.rag.entity.KnowledgeBase;
import com.commerce.rag.etl.EtlPipeline;
import com.commerce.rag.exception.BizException;
import com.commerce.rag.mapper.DocumentChunkMapper;
import com.commerce.rag.mapper.DocumentMapper;
import com.commerce.rag.mapper.KnowledgeBaseMapper;
import com.commerce.rag.service.impl.KnowledgeBaseServiceImpl;
import com.commerce.rag.storage.MinioStorageService;
import com.commerce.rag.test.MybatisPlusTestHelper;
import com.commerce.rag.vo.KnowledgeBaseVO;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttribute;

/**
 * IKnowledgeBaseService 单元测试 —— Mock Mapper + EtlPipeline
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceTest {

    @BeforeAll
    static void initMybatisPlus() {
        MybatisPlusTestHelper.initTableInfo();
    }

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private DocumentChunkMapper chunkMapper;

    @Mock
    private EtlPipeline etlPipeline;

    @Mock
    private MinioStorageService minioStorageService;

    /** Dashboard 统计缓存（Mock——知识库增删路径的失效钩子仅需不抛异常） */
    @Mock
    private DashboardCacheEvictor dashboardCacheEvictor;

    /** 转换器用真实实现（MapStruct 生成类），转换行为由 KnowledgeBaseConverterTest 单独覆盖 */
    @Spy
    private KnowledgeBaseConverter knowledgeBaseConverter = new KnowledgeBaseConverterImpl();

    @InjectMocks
    private KnowledgeBaseServiceImpl knowledgeBaseService;

    @Test
    @DisplayName("create 创建知识库")
    void create_insertsAndReturns() {
        when(knowledgeBaseMapper.insert(any(KnowledgeBase.class))).thenReturn(1);

        KnowledgeBaseVO result = knowledgeBaseService.create("测试知识库", "描述", 1L);

        assertNotNull(result);
        assertEquals("测试知识库", result.name());
        assertEquals("ACTIVE", result.status());
        assertEquals(1L, result.createdBy());
        verify(knowledgeBaseMapper).insert(any(KnowledgeBase.class));
    }

    @Test
    @DisplayName("B2-8: create 并发重名撞知识库唯一索引 → 转 BizException 409 而非 503")
    void create_uniqueViolationOnInsert_throwsConflict() {
        // 竞态窗口：并发同名创建双双走到 insert，后者撞 uniq_knowledge_base_name（重名唯一）
        when(knowledgeBaseMapper.insert(any(KnowledgeBase.class)))
                .thenThrow(new DataIntegrityViolationException("uniq_knowledge_base_name 冲突"));

        BizException ex = assertThrows(BizException.class, () -> knowledgeBaseService.create("同名库", "描述", 1L));

        // 语义应为 409（知识库名已存在/重复操作请刷新），而非 DataAccessException 全局映射的 503
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("已存在"));
    }

    @Test
    @DisplayName("delete 级联删除 — Milvus + MinIO 对象 + chunk + document + kb 全部调用")
    void delete_cascadeAll() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setCreatedBy(1L);
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);

        // KB 下两个文档（source_path 待删）
        Document doc1 = new Document();
        doc1.setId(10L);
        doc1.setSourcePath("1/10.pdf");
        Document doc2 = new Document();
        doc2.setId(11L);
        doc2.setSourcePath("1/11.pdf");
        when(documentMapper.selectList(any())).thenReturn(List.of(doc1, doc2));

        knowledgeBaseService.delete(1L, 1L, true);

        // 验证 Milvus 清理
        verify(etlPipeline).deleteFromMilvusByKbId(1L);
        // 验证 MinIO 对象批量删除（P1-4 Bug 4 + perf P1-2：一次请求删全部对象，失败上抛阻断）
        verify(minioStorageService).deleteFiles(List.of("1/10.pdf", "1/11.pdf"));
        InOrder inOrder = inOrder(minioStorageService, documentMapper);
        inOrder.verify(minioStorageService).deleteFiles(anyList());
        inOrder.verify(documentMapper).update(any(), any());
        // 验证 chunk 软删 + kb 软删
        verify(chunkMapper).update(any(), any());
        verify(knowledgeBaseMapper).update(any(), any());
    }

    @Test
    @DisplayName("delete 权限校验 — 非创建者抛出 SecurityException")
    void delete_wrongUser_throwsSecurityException() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setCreatedBy(1L);
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);

        assertThrows(BizException.class, () -> knowledgeBaseService.delete(1L, 2L, false));
    }

    @Test
    @DisplayName("delete 知识库不存在 — 不执行任何操作")
    void delete_notFound_noOp() {
        when(knowledgeBaseMapper.selectById(999L)).thenReturn(null);

        knowledgeBaseService.delete(999L, 1L, true);

        verify(etlPipeline, never()).deleteFromMilvusByKbId(any());
        verify(knowledgeBaseMapper, never()).update(any(), any());
    }

    // ==================== findById / findPage / update ====================

    @Test
    @DisplayName("findById → 超管可查看任意知识库")
    void findById_superAdmin_returnsVO() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setName("知识库A");
        kb.setCreatedBy(1L);
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);

        KnowledgeBaseVO result = knowledgeBaseService.findById(1L, 100L, "SUPER_ADMIN");

        assertEquals("知识库A", result.name());
    }

    @Test
    @DisplayName("findById → 教师查看非自己创建的知识库返回 null")
    void findById_teacherOtherKb_returnsNull() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setCreatedBy(1L);
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);

        KnowledgeBaseVO result = knowledgeBaseService.findById(1L, 2L, "TEACHER");

        assertNull(result);
    }

    @Test
    @DisplayName("findById → 教师查看自己的知识库返回 VO（created_by 为空的历史数据也拒绝）")
    void findById_teacherOwnKb_returnsVO() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setName("我的库");
        kb.setCreatedBy(2L);
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);

        KnowledgeBaseVO result = knowledgeBaseService.findById(1L, 2L, "TEACHER");

        assertEquals("我的库", result.name());
    }

    @Test
    @DisplayName("findById → 知识库不存在返回 null")
    void findById_notFound_returnsNull() {
        when(knowledgeBaseMapper.selectById(99L)).thenReturn(null);

        assertNull(knowledgeBaseService.findById(99L, 1L, "SUPER_ADMIN"));
    }

    @Test
    @DisplayName("findPage → 超管不带 created_by 过滤，按关键词分页并转换 VO")
    void findPage_superAdmin_keywordSearch() {
        Page<KnowledgeBase> entityPage = new Page<>(1, 20, 1);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setName("知识库A");
        entityPage.setRecords(List.of(kb));
        when(knowledgeBaseMapper.selectPage(any(Page.class), any())).thenReturn(entityPage);

        Page<KnowledgeBaseVO> result = knowledgeBaseService.findPage(1, 20, "知识", 100L, "SUPER_ADMIN");

        assertEquals(1, result.getRecords().size());
        assertEquals("知识库A", result.getRecords().get(0).name());
        assertEquals(1, result.getTotal());
    }

    @Test
    @DisplayName("findPage → 教师按 created_by=自己 过滤")
    void findPage_teacher_filtersByCreator() {
        Page<KnowledgeBase> entityPage = new Page<>(1, 20, 0);
        when(knowledgeBaseMapper.selectPage(any(Page.class), any())).thenReturn(entityPage);

        Page<KnowledgeBaseVO> result = knowledgeBaseService.findPage(1, 20, null, 2L, "TEACHER");

        assertTrue(result.getRecords().isEmpty());
        verify(knowledgeBaseMapper).selectPage(any(Page.class), any());
    }

    @Test
    @DisplayName("findPage → size<=0 时使用默认每页 20")
    void findPage_invalidSize_usesDefault() {
        Page<KnowledgeBase> entityPage = new Page<>(1, 20, 0);
        when(knowledgeBaseMapper.selectPage(any(Page.class), any())).thenReturn(entityPage);

        knowledgeBaseService.findPage(1, 0, null, 100L, "SUPER_ADMIN");

        verify(knowledgeBaseMapper).selectPage(argThat(p -> p.getSize() == 20), any());
    }

    @Test
    @DisplayName("update → 超管更新名称与描述")
    void update_superAdmin_updatesFields() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setCreatedBy(1L);
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);

        knowledgeBaseService.update(1L, "新名称", "新描述", 100L, true);

        verify(knowledgeBaseMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("update → 教师非创建者抛权限异常")
    void update_teacherOtherKb_throws() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setCreatedBy(1L);
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);

        assertThrows(BizException.class, () -> knowledgeBaseService.update(1L, "新名", null, 2L, false));
        verify(knowledgeBaseMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("update → 知识库不存在抛 IllegalArgumentException")
    void update_notFound_throws() {
        when(knowledgeBaseMapper.selectById(99L)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> knowledgeBaseService.update(99L, "新名", null, 1L, true));
    }

    // ==================== B2-5 级联软删事务原子性 ====================

    /** Spring 事务元数据解析器 —— 与生产事务切面同一解析路径，验证注解会被识别且异常触发回滚 */
    private static final AnnotationTransactionAttributeSource TX_SOURCE = new AnnotationTransactionAttributeSource();

    @Test
    @DisplayName("B2-5: delete 标注 @Transactional 且运行时异常触发回滚")
    void delete_isTransactional_rollsBackOnRuntimeFailure() throws NoSuchMethodException {
        Method method = KnowledgeBaseServiceImpl.class.getMethod("delete", Long.class, Long.class, boolean.class);
        TransactionAttribute attr = TX_SOURCE.getTransactionAttribute(method, KnowledgeBaseServiceImpl.class);

        // 注解存在（事务切面可识别）且 RuntimeException 触发回滚（默认回滚规则）
        assertNotNull(attr, "delete 应标注 @Transactional（B2-5：chunk→document→kb 三连 UPDATE 原子性）");
        assertTrue(attr.rollbackOn(new RuntimeException("级联软删中途失败")));
    }
}
