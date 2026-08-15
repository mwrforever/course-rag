package com.commerce.rag.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.commerce.rag.entity.ChatMessage;
import com.commerce.rag.entity.ChatRun;
import com.commerce.rag.entity.ChatSession;
import com.commerce.rag.entity.CourseContent;
import com.commerce.rag.entity.CourseEnrollment;
import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.entity.CourseSchedule;
import com.commerce.rag.entity.CourseTeacher;
import com.commerce.rag.entity.Document;
import com.commerce.rag.entity.DocumentChunk;
import com.commerce.rag.entity.KnowledgeBase;
import com.commerce.rag.entity.SysLoginRecord;
import com.commerce.rag.entity.SysTokenBlacklist;
import com.commerce.rag.entity.SysUser;
import com.commerce.rag.entity.UserFeedback;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * MyBatis-Plus 单元测试辅助工具
 *
 * <p>纯 Mockito 单元测试（无 Spring 上下文）中，LambdaQueryWrapper / LambdaUpdateWrapper
 * 需要实体的 TableInfo 缓存才能解析 lambda → 列名。此工具类在测试前统一初始化缓存。
 *
 * <p>使用方式：在受影响的测试类中添加
 * <pre>{@code
 * @BeforeAll
 * static void initMybatisPlus() {
 *     MybatisPlusTestHelper.initTableInfo();
 * }
 * }</pre>
 *
 * @author commerce-rag
 */
public final class MybatisPlusTestHelper {

    private MybatisPlusTestHelper() {}

    /**
     * 初始化所有项目实体的 TableInfo 缓存
     *
     * <p>TableInfoHelper 内部使用 ConcurrentHashMap 缓存，重复调用是幂等的。
     */
    public static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");

        // 初始化所有项目实体（按包名分组）
        TableInfoHelper.initTableInfo(assistant, DocumentChunk.class);
        TableInfoHelper.initTableInfo(assistant, Document.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeBase.class);
        TableInfoHelper.initTableInfo(assistant, SysUser.class);
        TableInfoHelper.initTableInfo(assistant, SysLoginRecord.class);
        TableInfoHelper.initTableInfo(assistant, SysTokenBlacklist.class);
        TableInfoHelper.initTableInfo(assistant, UserFeedback.class);
        TableInfoHelper.initTableInfo(assistant, ChatSession.class);
        TableInfoHelper.initTableInfo(assistant, ChatRun.class);
        TableInfoHelper.initTableInfo(assistant, ChatMessage.class);
        TableInfoHelper.initTableInfo(assistant, CourseInfo.class);
        TableInfoHelper.initTableInfo(assistant, CourseContent.class);
        TableInfoHelper.initTableInfo(assistant, CourseSchedule.class);
        TableInfoHelper.initTableInfo(assistant, CourseTeacher.class);
        TableInfoHelper.initTableInfo(assistant, CourseEnrollment.class);
    }
}
