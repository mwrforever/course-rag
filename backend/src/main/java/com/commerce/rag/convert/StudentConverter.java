package com.commerce.rag.convert;

import com.commerce.rag.entity.ChatMessage;
import com.commerce.rag.entity.ChatSession;
import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.entity.DocumentChunk;
import com.commerce.rag.record.AttachmentRecord;
import com.commerce.rag.record.RetrievalSource;
import com.commerce.rag.vo.ChunkBriefVO;
import com.commerce.rag.vo.ChunkContextVO;
import com.commerce.rag.vo.ChunkVO;
import com.commerce.rag.vo.SessionVO;
import com.commerce.rag.vo.StudentCourseVO;
import com.commerce.rag.vo.StudentMessageVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 学生端转换器 —— 实体 → C 端视图对象
 *
 * <p>MapStruct 编译期生成实现，禁止手写转换（工程宪法）。
 * 内部管理字段（deleted/时间戳/向量等）因 VO 无对应组件而自然忽略；
 * 关联分片（parent/prev/next）为 null 时由 MapStruct 生成 null 安全映射。
 *
 * <p>ObjectMapper 以接口常量形式持有（线程安全无状态，MapStruct 1.6.3 生成的 Impl
 * 只调用无参构造器，抽象类构造注入不可行；与 CourseConverter.JSON_MAPPER 同款方案）。
 *
 * @author commerce-rag
 */
@Mapper(componentModel = "spring")
public interface StudentConverter {

    Logger log = LoggerFactory.getLogger(StudentConverter.class);

    ObjectMapper JSON_MAPPER = new ObjectMapper();

    /** 课程实体 → 学生课程视图对象（不含价格/标签等内部字段） */
    StudentCourseVO toCourseVO(CourseInfo course);

    /** 分片实体 → 资料分片视图对象（含起止页） */
    ChunkVO toChunkVO(DocumentChunk chunk);

    /** 分片实体 → 简略视图对象（列表与关联分片用） */
    ChunkBriefVO toChunkBriefVO(DocumentChunk chunk);

    /** 分片实体 + 父/前/后分片 → 分片上下文视图对象（关联分片可空） */
    @Mapping(target = "id", source = "chunk.id")
    @Mapping(target = "docId", source = "chunk.docId")
    @Mapping(target = "kbId", source = "chunk.kbId")
    @Mapping(target = "content", source = "chunk.content")
    @Mapping(target = "headingPath", source = "chunk.headingPath")
    @Mapping(target = "chunkIndex", source = "chunk.chunkIndex")
    @Mapping(target = "courseId", source = "chunk.courseId")
    @Mapping(target = "parentChunkId", source = "chunk.parentChunkId")
    @Mapping(target = "prevChunkId", source = "chunk.prevChunkId")
    @Mapping(target = "nextChunkId", source = "chunk.nextChunkId")
    @Mapping(target = "parent", source = "parent")
    @Mapping(target = "prev", source = "prev")
    @Mapping(target = "next", source = "next")
    ChunkContextVO toChunkContextVO(DocumentChunk chunk, DocumentChunk parent, DocumentChunk prev, DocumentChunk next);

    /** 会话实体 → 会话视图对象 */
    SessionVO toSessionVO(ChatSession session);

    /**
     * 消息实体 → 学生历史消息视图对象（R1 补口 A）
     *
     * <p>同名字段直映射；sourcesJson/attachmentsJson（JSONB 字符串）经 @Named
     * 方法解析为对象数组，与实时 SSE SOURCES 事件/附件载荷同构，前端免区分链路。
     *
     * @param message 消息实体（service 两步查询投影行，含 sources_json/attachments_json）
     * @return 学生消息 VO（非法 JSON 兜底空列表，不阻断历史回显）
     */
    @Mapping(target = "sources", source = "sourcesJson", qualifiedByName = "parseSources")
    @Mapping(target = "attachments", source = "attachmentsJson", qualifiedByName = "parseAttachments")
    StudentMessageVO toStudentMessageVO(ChatMessage message);

    /**
     * sources JSON 字符串 → 引用来源列表
     *
     * @param sourcesJson chat_message.sources_json 列（JSON 数组字符串），可空/可非法
     * @return 解析后的来源列表；null/空白/非法 JSON 返回空列表（数据不一致不阻断历史回显）
     */
    @Named("parseSources")
    default List<RetrievalSource> parseSources(String sourcesJson) {
        if (sourcesJson == null || sourcesJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return JSON_MAPPER.readValue(sourcesJson, new TypeReference<List<RetrievalSource>>() {});
        } catch (Exception e) {
            // 兜底：损坏的 sources_json 不阻断历史消息接口，按无引用来源处理
            log.warn("解析消息引用来源 JSON 失败，兜底空列表: sourcesJson={}", sourcesJson);
            return Collections.emptyList();
        }
    }

    /**
     * attachments JSON 字符串 → 用户附件列表
     *
     * @param attachmentsJson chat_message.attachments_json 列（JSON 数组字符串），可空/可非法
     * @return 解析后的附件列表；null/空白/非法 JSON 返回空列表（assistant 行恒空）
     */
    @Named("parseAttachments")
    default List<AttachmentRecord> parseAttachments(String attachmentsJson) {
        if (attachmentsJson == null || attachmentsJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return JSON_MAPPER.readValue(attachmentsJson, new TypeReference<List<AttachmentRecord>>() {});
        } catch (Exception e) {
            // 兜底：损坏的 attachments_json 不阻断历史消息接口，按无附件处理
            log.warn("解析消息附件 JSON 失败，兜底空列表: attachmentsJson={}", attachmentsJson);
            return Collections.emptyList();
        }
    }
}
