package com.commerce.rag.controller.vo;

/**
 * 资料分片视图对象 —— controller 出参（C 端接口 J2 课程专属资料）
 *
 * <p>与 DocumentChunk 实体同名业务字段一一对应，剔除内部字段
 * （docId/kbId/tokenCount/collectionType/courseId/metadataJson/milvusPk/
 * 前后分片指针/字符偏移/纠错状态/denseVector/deleted/时间戳）。
 *
 * @param id          分片 ID
 * @param content     分片内容
 * @param headingPath 标题路径（多级标题用 / 分隔）
 * @param chunkIndex  分片序号
 * @param parentTitle 所属章节标题
 * @param startPage   起始页
 * @param endPage     结束页
 */
public record ChunkVO(
        Long id,
        String content,
        String headingPath,
        Integer chunkIndex,
        String parentTitle,
        Integer startPage,
        Integer endPage) {}
