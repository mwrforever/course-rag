package com.commerce.rag.knowledge;

import com.commerce.rag.common.exception.RagBusinessException;
import com.commerce.rag.common.result.ApiResult;
import com.commerce.rag.etl.pipeline.EtlPipeline;
import com.commerce.rag.knowledge.entity.Document;
import com.commerce.rag.knowledge.entity.DocumentRepository;
import com.commerce.rag.knowledge.entity.KnowledgeBase;
import com.commerce.rag.knowledge.entity.KnowledgeBaseRepository;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * 知识库管理 API 控制器
 *
 * 提供知识库 CRUD 和文档上传接口。
 * 文档上传后自动触发 ETL 管道（解析 → 分块 → 向量化 → 存储）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeBaseController {

    private final KnowledgeBaseRepository kbRepository;
    private final DocumentRepository documentRepository;
    private final EtlPipeline etlPipeline;

    public KnowledgeBaseController(KnowledgeBaseRepository kbRepository,
                                   DocumentRepository documentRepository,
                                   EtlPipeline etlPipeline) {
        this.kbRepository = kbRepository;
        this.documentRepository = documentRepository;
        this.etlPipeline = etlPipeline;
    }

    /**
     * 创建知识库
     */
    @PostMapping("/bases")
    public ApiResult<KnowledgeBase> createKnowledgeBase(@RequestBody CreateKbRequest request) {
        KnowledgeBase kb = KnowledgeBase.builder()
                .name(request.getName())
                .description(request.getDescription())
                .build();
        kb = kbRepository.save(kb);
        log.info("知识库创建成功: id={}, name={}", kb.getId(), kb.getName());
        return ApiResult.ok(kb);
    }

    /**
     * 查询所有知识库
     */
    @GetMapping("/bases")
    public ApiResult<List<KnowledgeBase>> listKnowledgeBases() {
        return ApiResult.ok(kbRepository.findAll());
    }

    /**
     * 查询知识库下的所有文档
     */
    @GetMapping("/bases/{kbId}/documents")
    public ApiResult<List<Document>> listDocuments(@PathVariable UUID kbId) {
        return ApiResult.ok(documentRepository.findByKbId(kbId));
    }

    /**
     * 上传文档到知识库（自动触发 ETL）
     *
     * @param kbId 知识库 ID
     * @param file 上传的文件
     * @return 文档记录（含 ETL 状态）
     */
    @PostMapping("/bases/{kbId}/documents")
    public ApiResult<Document> uploadDocument(@PathVariable UUID kbId,
                                               @RequestParam("file") MultipartFile file) {
        // 校验知识库存在
        KnowledgeBase kb = kbRepository.findById(kbId)
                .orElseThrow(() -> new RagBusinessException(404, "知识库不存在: " + kbId));

        String fileName = file.getOriginalFilename();
        log.info("文档上传: kbId={}, fileName={}, size={}", kbId, fileName, file.getSize());

        try {
            // 创建文档记录
            Document doc = Document.builder()
                    .kbId(kbId)
                    .title(fileName)
                    .fileType(extractFileType(fileName))
                    .fileSize(file.getSize())
                    .parseStatus("UPLOADED")
                    .build();
            doc = documentRepository.save(doc);

            // 异步触发 ETL
            byte[] fileData = file.getBytes();
            etlPipeline.processAsync(doc.getId(), fileData, fileName);

            log.info("文档 ETL 已触发: docId={}, fileName={}", doc.getId(), fileName);
            return ApiResult.ok(doc);

        } catch (Exception e) {
            log.error("文档上传失败: fileName={}", fileName, e);
            throw new RagBusinessException("文档上传失败: " + e.getMessage());
        }
    }

    /**
     * 查询文档详情（含 ETL 状态）
     */
    @GetMapping("/documents/{docId}")
    public ApiResult<Document> getDocument(@PathVariable UUID docId) {
        Document doc = documentRepository.findById(docId)
                .orElseThrow(() -> new RagBusinessException(404, "文档不存在: " + docId));
        return ApiResult.ok(doc);
    }

    private String extractFileType(String fileName) {
        if (fileName == null || !fileName.contains(".")) return "UNKNOWN";
        return fileName.substring(fileName.lastIndexOf('.') + 1).toUpperCase();
    }

    @Data
    public static class CreateKbRequest {
        private String name;
        private String description;
    }
}
