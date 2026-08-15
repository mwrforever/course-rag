package com.commerce.rag.controller;

import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.controller.dto.ApiResponse;
import com.commerce.rag.controller.dto.BatchChunkUpdateRequest;
import com.commerce.rag.controller.dto.BatchCorrectedRequest;
import com.commerce.rag.controller.dto.ChunkCollectionTypeRequest;
import com.commerce.rag.controller.dto.ChunkContentUpdateRequest;
import com.commerce.rag.controller.dto.PageResponse;
import com.commerce.rag.entity.DocumentChunk;
import com.commerce.rag.service.DocumentChunkService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分片管理 Controller（D1-D9）
 *
 * <p>B 端管理接口，教师/超级管理员可操作。
 * 支持分片查看、内容修正、标量字段批量更新、上下文查询等。
 * TEACHER 只能操作自己创建的文档的分片（Service 层经 doc_id→document.created_by 校验）。
 * 依赖注入：Lombok @RequiredArgsConstructor 构造器注入（private final DocumentChunkService chunkService）。
 *
 * @author commerce-rag
 */
@RestController
@RequestMapping("/api/v1/admin/chunks")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TEACHER')")
@RequiredArgsConstructor
public class AdminChunkController {

    private static final Logger log = LoggerFactory.getLogger(AdminChunkController.class);

    private final DocumentChunkService chunkService;

    /** D1: 查询分片详情 */
    @GetMapping("/{id}")
    public ApiResponse<DocumentChunk> findById(HttpServletRequest request, @PathVariable Long id) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        String role = AuthInterceptor.getCurrentRole(request);
        DocumentChunk chunk = chunkService.findById(id, userId, role);
        if (chunk == null) {
            return ApiResponse.error(404, "分片不存在");
        }
        return ApiResponse.ok(chunk);
    }

    /** D2: 分页查询分片 */
    @GetMapping
    public ApiResponse<PageResponse<DocumentChunk>> findPage(
            HttpServletRequest request,
            @RequestParam(required = false) Long docId,
            @RequestParam(required = false) Long kbId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        String role = AuthInterceptor.getCurrentRole(request);
        return ApiResponse.ok(PageResponse.of(chunkService.findPage(docId, kbId, page, size, userId, role)));
    }

    /** D3: 更新分片内容（重新向量化）—— 契约对齐：前端文档 :933 PUT /api/v1/admin/chunks/{id} */
    @PutMapping("/{id}")
    public ApiResponse<Void> updateContent(
            HttpServletRequest request, @PathVariable Long id, @RequestBody ChunkContentUpdateRequest request2) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        chunkService.updateContent(id, request2.content(), userId, isAdmin);
        return ApiResponse.ok();
    }

    /** D4: 删除分片 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable Long id) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        chunkService.delete(id, userId, isAdmin);
        return ApiResponse.ok();
    }

    /** D5: 更新分片 collection_type/course_id（不重新向量化） — PATCH 局部更新 */
    @PatchMapping("/{id}/collection-type")
    public ApiResponse<Void> updateCollectionType(
            HttpServletRequest request, @PathVariable Long id, @RequestBody ChunkCollectionTypeRequest request2) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        chunkService.updateCollectionType(id, request2.collectionType(), request2.courseId(), userId, isAdmin);
        return ApiResponse.ok();
    }

    /** D6: 查询分片上下文（parent/prev/current/next） */
    @GetMapping("/{id}/context")
    public ApiResponse<Map<String, DocumentChunk>> findContext(HttpServletRequest request, @PathVariable Long id) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        String role = AuthInterceptor.getCurrentRole(request);
        return ApiResponse.ok(chunkService.findContext(id, userId, role));
    }

    /** D7: 批量更新标量字段（collection_type/course_id） — POST /batch-update */
    @PostMapping("/batch-update")
    public ApiResponse<Void> batchUpdate(HttpServletRequest request, @RequestBody BatchChunkUpdateRequest request2) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        chunkService.batchUpdate(request2.ids(), request2.collectionType(), request2.courseId(), userId, isAdmin);
        return ApiResponse.ok();
    }

    /** D8: 批量标记已修正 —— 契约对齐：前端文档 :926 POST /api/v1/admin/chunks/batch-corrected */
    @PostMapping("/batch-corrected")
    public ApiResponse<Void> batchCorrected(HttpServletRequest request, @RequestBody BatchCorrectedRequest request2) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        chunkService.batchCorrected(request2.ids(), userId, isAdmin);
        return ApiResponse.ok();
    }

    /** D9: 查询待修正分片 */
    @GetMapping("/pending")
    public ApiResponse<PageResponse<DocumentChunk>> findPending(
            HttpServletRequest request,
            @RequestParam(required = false) Long kbId,
            @RequestParam(required = false) Long docId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        String role = AuthInterceptor.getCurrentRole(request);
        return ApiResponse.ok(PageResponse.of(chunkService.findPending(kbId, docId, page, size, userId, role)));
    }
}
