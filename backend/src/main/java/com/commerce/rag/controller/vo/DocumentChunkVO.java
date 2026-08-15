package com.commerce.rag.controller.vo;

import java.time.LocalDateTime;

/**
 * 文档分片视图对象 —— controller 出参（B 端管理接口）
 *
 * <p>与 DocumentChunk 实体同名业务字段一一对应，剔除敏感列：
 * <ul>
 *   <li>denseVector（PG 冗余存储的 embedding 字节）—— 向量密文，不对外暴露</li>
 *   <li>deleted（逻辑删除标记）—— 内部数据管理字段</li>
 * </ul>
 *
 * @param id              分片 ID
 * @param docId           所属文档 ID
 * @param kbId            所属知识库 ID
 * @param chunkIndex      分片序号（同文档内递增）
 * @param content         分片文本内容
 * @param headingPath     标题路径（如 "第一章 > 1.1 概述"）
 * @param parentTitle     父标题
 * @param startPage       起始页码
 * @param endPage         结束页码
 * @param tokenCount      Token 数量
 * @param collectionType  Milvus 标量路由字段（TECHNICAL_QA / COURSE_INFO）
 * @param courseId        课程关联 ID（默认 DEFAULT）
 * @param metadataJson    元数据 JSON（JSONB）
 * @param milvusPk        Milvus 主键（与 chunk_id 字段一致）
 * @param parentChunkId   父分片 ID
 * @param prevChunkId     前一个分片 ID
 * @param nextChunkId     后一个分片 ID
 * @param charOffsetStart 原文字符偏移起始
 * @param charOffsetEnd   原文字符偏移结束
 * @param correctionStatus 旁路修正状态（PENDING / CORRECTED）
 * @param createdAt       创建时间
 * @param updatedAt       更新时间
 */
public record DocumentChunkVO(
        Long id,
        Long docId,
        Long kbId,
        Integer chunkIndex,
        String content,
        String headingPath,
        String parentTitle,
        Integer startPage,
        Integer endPage,
        Integer tokenCount,
        String collectionType,
        String courseId,
        String metadataJson,
        String milvusPk,
        Long parentChunkId,
        Long prevChunkId,
        Long nextChunkId,
        Integer charOffsetStart,
        Integer charOffsetEnd,
        String correctionStatus,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {}
