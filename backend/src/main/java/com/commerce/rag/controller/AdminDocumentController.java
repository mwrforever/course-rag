package com.commerce.rag.controller;

import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.controller.dto.ApiResponse;
import com.commerce.rag.controller.dto.DocumentUpdateRequest;
import com.commerce.rag.controller.dto.PageResponse;
import com.commerce.rag.entity.Document;
import com.commerce.rag.etl.EtlProperties;
import com.commerce.rag.service.DocumentService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * 文档管理 Controller（C1-C7）
 *
 * <p>B 端管理接口，教师/超级管理员可操作。
 * 上传文档后自动触发 ETL 异步管道。
 *
 * @author commerce-rag
 */
@RestController
@RequestMapping("/api/v1/admin/documents")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TEACHER')")
@RequiredArgsConstructor
public class AdminDocumentController {

    private static final Logger log = LoggerFactory.getLogger(AdminDocumentController.class);

    /**
     * 文件类型白名单（前端设计文档 2.6.2 限定：PDF/PPTX/DOCX/MD/TXT），
     * 防止 .exe/.zip 等任意类型文件堆积 FAILED 文档
     */
    private static final Set<String> ALLOWED_FILE_TYPES = Set.of("pdf", "docx", "pptx", "md", "txt");

    private final DocumentService documentService;

    private final EtlProperties etlProperties;

    /** C1: 上传文档（courseId 可选：不传则 DEFAULT=通用资料库，传则文档归属该课程，分片继承） */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Document> upload(
            HttpServletRequest request,
            @RequestParam("kbId") Long kbId,
            @RequestParam("title") String title,
            @RequestParam(value = "courseId", required = false) String courseId,
            @RequestParam("file") MultipartFile file)
            throws IOException {

        Long userId = AuthInterceptor.getCurrentUserId(request);
        String role = AuthInterceptor.getCurrentRole(request);
        String originalFilename = file.getOriginalFilename();
        String fileType = extractFileExtension(originalFilename);
        Long fileSize = file.getSize();

        // P2-1: 文件类型白名单（前端文档限定 PDF/PPTX/DOCX/MD/TXT，防 .exe/.zip 等任意类型堆积 FAILED）
        if (!ALLOWED_FILE_TYPES.contains(fileType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的文件类型: " + fileType);
        }
        // P2-1: 大小校验（引用 etl.max-file-size-mb 配置，修复死配置）
        if (fileSize > etlProperties.maxFileSizeMb() * 1024 * 1024L) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "文件大小超过限制: " + etlProperties.maxFileSizeMb() + "MB");
        }

        try (InputStream inputStream = file.getInputStream()) {
            Document doc = documentService.upload(
                    kbId, title, inputStream, fileType, fileSize, courseId, userId, "SUPER_ADMIN".equals(role));
            return ApiResponse.ok(doc);
        }
    }

    /** C2: 查询文档详情 */
    @GetMapping("/{id}")
    public ApiResponse<Document> findById(HttpServletRequest request, @PathVariable Long id) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        String role = AuthInterceptor.getCurrentRole(request);
        Document doc = documentService.findById(id, userId, role);
        if (doc == null) {
            // P1-3: 内联 404 双轨修复——统一走 ResponseStatusException（真实 HTTP 404）
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在");
        }
        return ApiResponse.ok(doc);
    }

    /** C3: 分页查询文档（P2-2：status/q/sort 筛选参数对齐前端文档 :871） */
    @GetMapping
    public ApiResponse<PageResponse<Document>> findPage(
            HttpServletRequest request,
            @RequestParam(required = false) Long kbId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        String role = AuthInterceptor.getCurrentRole(request);
        return ApiResponse.ok(
                PageResponse.of(documentService.findPage(kbId, status, q, sort, page, size, userId, role)));
    }

    /** C4: 更新文档标题 */
    @PutMapping("/{id}")
    public ApiResponse<Void> update(
            HttpServletRequest request, @PathVariable Long id, @RequestBody DocumentUpdateRequest request2) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        documentService.update(id, request2.title(), userId, isAdmin);
        return ApiResponse.ok();
    }

    /** C5: 删除文档（级联） */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable Long id) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        documentService.delete(id, userId, isAdmin);
        return ApiResponse.ok();
    }

    /** C6: 重新解析文档 */
    @PostMapping("/{id}/reparse")
    public ApiResponse<Void> reparse(HttpServletRequest request, @PathVariable Long id) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        documentService.reparse(id, userId, isAdmin);
        return ApiResponse.ok();
    }

    /** C7: 下载文档原始文件（perf P2-4：download 一次查询取实体，fileType 由实体带出，消除重复主键查询） */
    @GetMapping("/{id}/download")
    public org.springframework.core.io.Resource download(HttpServletRequest request, @PathVariable Long id) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        DocumentService.DocumentDownload download = documentService.downloadWithType(id, userId, isAdmin);
        return new InputStreamResource(download.inputStream()) {
            @Override
            public String getFilename() {
                return "document-" + id + (download.fileType() != null ? "." + download.fileType() : "");
            }
        };
    }

    /**
     * 从文件名提取扩展名（小写）
     */
    private String extractFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "bin";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }
}
