package com.commerce.rag.service;

import com.commerce.rag.controller.vo.ChunkBriefVO;
import com.commerce.rag.controller.vo.ChunkContextVO;
import com.commerce.rag.controller.vo.ChunkVO;
import com.commerce.rag.controller.vo.SessionVO;
import com.commerce.rag.controller.vo.StudentCourseVO;
import com.commerce.rag.entity.ChatSession;
import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.entity.DocumentChunk;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * 学生端转换器 —— 实体 → C 端视图对象
 *
 * <p>MapStruct 编译期生成实现，禁止手写转换（工程宪法）。
 * 内部管理字段（deleted/时间戳/向量等）因 VO 无对应组件而自然忽略；
 * 关联分片（parent/prev/next）为 null 时由 MapStruct 生成 null 安全映射。
 *
 * @author commerce-rag
 */
@Mapper(componentModel = "spring")
public interface StudentConverter {

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
}
