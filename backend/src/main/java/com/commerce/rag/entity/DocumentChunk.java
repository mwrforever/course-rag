package com.commerce.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 文档分片实体 —— 对应 document_chunk 表
 *
 * <p>文档分片是 ETL 管道的产物，每条记录对应一段文本 + Milvus 向量。
 * 核心字段：
 * <ul>
 *   <li>content — 分片文本内容</li>
 *   <li>collection_type — Milvus 标量路由字段（TECHNICAL_QA / COURSE_INFO）</li>
 *   <li>course_id — 课程关联 ID（默认 DEFAULT）</li>
 *   <li>dense_vector — PG 冗余存储的 embedding 字节（避免回查 Milvus）</li>
 *   <li>correction_status — 旁路修正状态（PENDING / CORRECTED）</li>
 *   <li>parent_chunk_id / prev_chunk_id / next_chunk_id — 父子关联链</li>
 *   <li>char_offset_start / char_offset_end — 原文字符偏移</li>
 *   <li>milvus_pk — Milvus 主键（与 chunk_id 字段一致）</li>
 * </ul>
 *
 * @author commerce-rag
 */
@Data
@TableName("document_chunk")
public class DocumentChunk implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 ID（雪花算法生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属文档 ID */
    @TableField("doc_id")
    private Long docId;

    /** 所属知识库 ID */
    @TableField("kb_id")
    private Long kbId;

    /** 分片序号（同文档内递增） */
    @TableField("chunk_index")
    private Integer chunkIndex;

    /** 分片文本内容 */
    private String content;

    /** 标题路径（如 "第一章 > 1.1 概述"） */
    @TableField("heading_path")
    private String headingPath;

    /** 父标题 */
    @TableField("parent_title")
    private String parentTitle;

    /** 起始页码 */
    @TableField("start_page")
    private Integer startPage;

    /** 结束页码 */
    @TableField("end_page")
    private Integer endPage;

    /** Token 数量 */
    @TableField("token_count")
    private Integer tokenCount;

    /** Milvus 标量路由字段（TECHNICAL_QA / COURSE_INFO） */
    @TableField("collection_type")
    private String collectionType;

    /** 课程关联 ID（默认 DEFAULT） */
    @TableField("course_id")
    private String courseId;

    /** 分片内容类型：text 文本 / image 图片（content=caption）/ table 表格（Markdown） */
    @TableField("content_type")
    private String contentType;

    /** 图片分片的 MinIO objectKey（仅 content_type=image 有值，其余为 null） */
    @TableField("image_url")
    private String imageUrl;

    /** 归一化内容的 SHA-256 十六进制摘要（64 字符，ETL 全局去重键） */
    @TableField("sha256")
    private String sha256;

    /** 元数据 JSON（JSONB） */
    @TableField("metadata_json")
    private String metadataJson;

    /** Milvus 主键（与 chunk_id 字段一致，用于 traceability） */
    @TableField("milvus_pk")
    private String milvusPk;

    /** 父分片 ID（大段落拆分时，首片为父） */
    @TableField("parent_chunk_id")
    private Long parentChunkId;

    /** 前一个分片 ID（线性链） */
    @TableField("prev_chunk_id")
    private Long prevChunkId;

    /** 后一个分片 ID（线性链） */
    @TableField("next_chunk_id")
    private Long nextChunkId;

    /** 原文字符偏移起始 */
    @TableField("char_offset_start")
    private Integer charOffsetStart;

    /** 原文字符偏移结束 */
    @TableField("char_offset_end")
    private Integer charOffsetEnd;

    /** 旁路修正状态（PENDING / CORRECTED） */
    @TableField("correction_status")
    private String correctionStatus;

    /** PG 冗余存储的 embedding 字节 */
    @TableField("dense_vector")
    private byte[] denseVector;

    /** 逻辑删除标记（0 = 未删除，1 = 已删除） */
    @TableLogic(value = "0", delval = "1")
    private Long deleted;

    /** 创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
