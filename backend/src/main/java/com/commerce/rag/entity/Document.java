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
 * 文档实体 —— 对应 document 表
 *
 * <p>文档是知识库下的文件记录，存储原始文件元信息与 ETL 状态。
 * parse_status 状态机：PENDING → PARSING → PARSED → CHUNKING → CHUNKED → EMBEDDING → INDEXED / FAILED。
 * source_path 存储 MinIO objectKey。
 * metadata_json 为 JSONB，存储 Tika 解析的元信息（作者、页数等）。
 *
 * @author commerce-rag
 */
@Data
@TableName("document")
public class Document implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 ID（雪花算法生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属知识库 ID */
    @TableField("kb_id")
    private Long kbId;

    /** 文档标题 */
    private String title;

    /** MinIO objectKey（文件路径：{kb_id}/{doc_id}.{ext}） */
    @TableField("source_path")
    private String sourcePath;

    /** 文件类型（pdf/docx/pptx/md 等） */
    @TableField("file_type")
    private String fileType;

    /** 文件大小（字节） */
    @TableField("file_size")
    private Long fileSize;

    /** 解析状态：PENDING / PARSING / PARSED / CHUNKING / CHUNKED / EMBEDDING / INDEXED / FAILED */
    @TableField("parse_status")
    private String parseStatus;

    /** 分片数量 */
    @TableField("chunk_count")
    private Integer chunkCount;

    /** 错误信息（parse_status=FAILED 时填充） */
    @TableField("error_message")
    private String errorMessage;

    /** 元数据 JSON（JSONB，Tika 解析的作者、页数等） */
    @TableField("metadata_json")
    private String metadataJson;

    /** 课程 ID（DEFAULT=通用资料库；上传时可指定文档归属课程，分片继承该值） */
    @TableField("course_id")
    private String courseId;

    /** 创建者 ID */
    @TableField("created_by")
    private Long createdBy;

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
