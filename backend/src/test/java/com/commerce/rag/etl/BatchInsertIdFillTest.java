package com.commerce.rag.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.commerce.rag.entity.DocumentChunk;
import com.commerce.rag.mapper.DocumentChunkMapper;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DocumentChunkMapper.batchInsert 的 ID 自动填充行为实测（P1-4 设计前提验证）
 *
 * <p>目的：实测当前 MyBatis-Plus 3.5.12 下，自定义 mapper 方法（@Param List 参数 +
 * XML foreach 批插）执行时 MP 是否自动填充实体 @TableId(ASSIGN_ID) 雪花 ID——
 * 决定批插是否需要显式注入 IdentifierGenerator 预生成。
 *
 * <p>验证方式：不连数据库，直接用 MP 真实组件链路（MybatisConfiguration +
 * MybatisXMLLanguageDriver.createParameterHandler + MyBatis ParamNameResolver 包装参数）。
 * MybatisParameterHandler 在构造时即执行 processParameter（仅 INSERT/UPDATE），
 * 对集合参数内每个实体执行 populateKeys 填充 ASSIGN_ID，故 createParameterHandler
 * 返回后即可断言实体 ID 已填充。运行时接线同链路：MybatisConfiguration 构造器把默认
 * LanguageDriver 设为 MybatisXMLLanguageDriver，项目所有 XML mapper（含本批插）均经此路径。
 *
 * <p>结论（实测通过则成立）：MP 自动填充集合参数内实体 ID，批插无需 IdentifierGenerator。
 *
 * @author commerce-rag
 */
class BatchInsertIdFillTest {

    @BeforeAll
    static void initTableInfo() {
        // 初始化 DocumentChunk 的 TableInfo（populateKeys 依赖其 idType/keyProperty 解析）
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, DocumentChunk.class);
    }

    /**
     * 实测：batchInsert（@Param List 参数）经 MP 参数处理器后每个实体 ID 非空且互不相同
     *
     * <p>若本用例失败（ID 为空），说明当前 MP 版本不填充集合参数，
     * 批插实现需切换为显式注入 IdentifierGenerator 预生成 ID。
     */
    @Test
    @DisplayName("MP 3.5.12 — batchInsert 集合参数自动填充 ASSIGN_ID 雪花 ID（无需 IdentifierGenerator）")
    void batchInsert_listParam_autoFillsAssignId() throws Exception {
        List<DocumentChunk> chunks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocId(1L);
            chunk.setContent("内容" + i);
            chunks.add(chunk);
        }

        // 用 MyBatis 真实 ParamNameResolver 包装参数——与运行时 @Param("chunks") 行为一致
        // （产出 ParamMap{chunks=List, param1=List}，模拟 mapper 代理调用时的参数形态）
        MybatisConfiguration configuration = new MybatisConfiguration();
        // 模拟 MybatisSqlSessionFactoryBean 构建时的 GlobalConfig 初始化——注册默认雪花生成器
        // （默认 GlobalConfig 的 identifierGenerator 为 null，工厂构建时才注入 DefaultIdentifierGenerator；
        //   真实应用由 starter 自动完成该初始化）
        GlobalConfig globalConfig = GlobalConfigUtils.defaults();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
        Method method = DocumentChunkMapper.class.getMethod("batchInsert", List.class);
        ParamNameResolver resolver = new ParamNameResolver(configuration, method);
        Object wrappedParams = resolver.getNamedParams(new Object[] {chunks});

        // 构造 INSERT 语义的 MappedStatement（XML 语句解析产物等价物）
        SqlSource sqlSource = params -> new org.apache.ibatis.mapping.BoundSql(
                configuration,
                "INSERT INTO document_chunk (id, doc_id) VALUES (#{c.id}, #{c.docId})",
                List.of(),
                params);
        MappedStatement ms = new MappedStatement.Builder(
                        configuration,
                        "com.commerce.rag.mapper.DocumentChunkMapper.batchInsert",
                        sqlSource,
                        SqlCommandType.INSERT)
                .build();

        // 触发 MP 参数处理器（构造 MybatisParameterHandler 时即填充 ID）
        new com.baomidou.mybatisplus.core.MybatisXMLLanguageDriver()
                .createParameterHandler(ms, wrappedParams, ms.getBoundSql(wrappedParams));

        // 断言：每个实体 ID 已被填充为雪花 ID（非空、为正、互不相同）
        for (DocumentChunk chunk : chunks) {
            assertNotNull(chunk.getId(), "MP 应自动填充集合参数内实体的 ASSIGN_ID");
            assertTrue(chunk.getId() > 0, "雪花 ID 应为正数");
        }
        assertEquals(3, chunks.stream().map(DocumentChunk::getId).distinct().count(), "各实体 ID 应互不相同");
    }
}
