package com.commerce.rag.vo;

/**
 * 资料分片简略视图对象 —— controller 出参（C 端接口 J3 通用资料库 / J4 分片上下文关联分片）
 *
 * <p>与 DocumentChunk 实体同名业务字段一一对应，仅暴露列表所需最小字段集。
 *
 * @param id          分片 ID
 * @param content     分片内容
 * @param headingPath 标题路径（多级标题用 / 分隔）
 * @param chunkIndex  分片序号
 * @param parentTitle 所属章节标题
 */
public record ChunkBriefVO(Long id, String content, String headingPath, Integer chunkIndex, String parentTitle) {}
