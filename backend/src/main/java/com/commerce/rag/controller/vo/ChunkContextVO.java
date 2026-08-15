package com.commerce.rag.controller.vo;

/**
 * 分片上下文视图对象 —— controller 出参（C 端接口 J4 分片上下文）
 *
 * <p>主分片字段 + 父/前/后关联分片（简略视图），关联分片不存在时为 null。
 *
 * @param id            分片 ID
 * @param docId         所属文档 ID
 * @param kbId          所属知识库 ID
 * @param content       分片内容
 * @param headingPath   标题路径（多级标题用 / 分隔）
 * @param chunkIndex    分片序号
 * @param courseId      课程 ID（DEFAULT=通用资料库）
 * @param parentChunkId 父分片 ID（章节聚合）
 * @param prevChunkId   前一分片 ID
 * @param nextChunkId   后一分片 ID
 * @param parent        父分片简略信息（可空）
 * @param prev          前一分片简略信息（可空）
 * @param next          后一分片简略信息（可空）
 */
public record ChunkContextVO(
        Long id,
        Long docId,
        Long kbId,
        String content,
        String headingPath,
        Integer chunkIndex,
        String courseId,
        Long parentChunkId,
        Long prevChunkId,
        Long nextChunkId,
        ChunkBriefVO parent,
        ChunkBriefVO prev,
        ChunkBriefVO next) {}
