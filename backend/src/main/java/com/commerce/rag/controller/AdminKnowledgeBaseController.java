package com.commerce.rag.controller;

import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.controller.dto.ApiResponse;
import com.commerce.rag.controller.dto.KnowledgeBaseRequest;
import com.commerce.rag.controller.dto.PageResponse;
import com.commerce.rag.exception.BizException;
import com.commerce.rag.exception.ErrorCode;
import com.commerce.rag.service.IKnowledgeBaseService;
import com.commerce.rag.vo.KnowledgeBaseVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * 知识库管理 Controller（B1-B5）
 *
 * <p>B 端管理接口，教师/超级管理员可操作。
 * Service 层校验 created_by，教师只能操作自己创建的知识库。
 *
 * @author commerce-rag
 */
@RestController
@RequestMapping("/api/v1/admin/knowledge-bases")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TEACHER')")
public class AdminKnowledgeBaseController {

    private static final Logger log = LoggerFactory.getLogger(AdminKnowledgeBaseController.class);

    private final IKnowledgeBaseService knowledgeBaseService;

    public AdminKnowledgeBaseController(IKnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    /** B1: 创建知识库（P2-12：@Valid 触发 name 非空校验，空 name → HTTP 400） */
    @PostMapping
    public ApiResponse<KnowledgeBaseVO> create(
            HttpServletRequest request, @Valid @RequestBody KnowledgeBaseRequest request2) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        KnowledgeBaseVO kb = knowledgeBaseService.create(request2.name(), request2.description(), userId);
        return ApiResponse.ok(kb);
    }

    /** B2: 查询知识库详情 */
    @GetMapping("/{id}")
    public ApiResponse<KnowledgeBaseVO> findById(HttpServletRequest request, @PathVariable Long id) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        String role = AuthInterceptor.getCurrentRole(request);
        KnowledgeBaseVO kb = knowledgeBaseService.findById(id, userId, role);
        if (kb == null) {
            // P1-3: 内联 404 双轨修复——统一走 ResponseStatusException（真实 HTTP 404）
            throw new BizException(ErrorCode.NOT_FOUND, "知识库不存在");
        }
        return ApiResponse.ok(kb);
    }

    /** B3: 分页查询知识库 */
    @GetMapping
    public ApiResponse<PageResponse<KnowledgeBaseVO>> findPage(
            HttpServletRequest request,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        String role = AuthInterceptor.getCurrentRole(request);
        return ApiResponse.ok(PageResponse.of(knowledgeBaseService.findPage(page, size, keyword, userId, role)));
    }

    /** B4: 更新知识库 */
    @PutMapping("/{id}")
    public ApiResponse<Void> update(
            HttpServletRequest request, @PathVariable Long id, @RequestBody KnowledgeBaseRequest request2) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        knowledgeBaseService.update(id, request2.name(), request2.description(), userId, isAdmin);
        return ApiResponse.ok();
    }

    /** B5: 删除知识库（级联） */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable Long id) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        knowledgeBaseService.delete(id, userId, isAdmin);
        return ApiResponse.ok();
    }
}
