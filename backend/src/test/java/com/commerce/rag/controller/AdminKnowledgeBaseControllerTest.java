package com.commerce.rag.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.controller.dto.ApiResponse;
import com.commerce.rag.controller.dto.KnowledgeBaseRequest;
import com.commerce.rag.controller.dto.PageResponse;
import com.commerce.rag.controller.vo.KnowledgeBaseVO;
import com.commerce.rag.service.KnowledgeBaseService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * AdminKnowledgeBaseController 单元测试 —— 知识库管理端点 B1-B5
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminKnowledgeBaseController 知识库端点测试")
class AdminKnowledgeBaseControllerTest {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    private AdminKnowledgeBaseController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminKnowledgeBaseController(knowledgeBaseService);
    }

    private HttpServletRequest request(String role, Long userId) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(userId);
        when(req.getAttribute(AuthInterceptor.ATTR_ROLE)).thenReturn(role);
        return req;
    }

    private KnowledgeBaseVO kb(Long id) {
        return new KnowledgeBaseVO(id, "知识库" + id, "描述", "ACTIVE", 1L, LocalDateTime.now(), null);
    }

    @Test
    @DisplayName("B1 create → 调用 service 创建并返回 VO")
    void create_callsService() {
        // create 端点仅读取 userId（不读角色），故只 stub userId
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(1L);
        when(knowledgeBaseService.create("知识库A", "描述", 1L)).thenReturn(kb(1L));

        ApiResponse<KnowledgeBaseVO> result =
                controller.create(req, new KnowledgeBaseRequest("知识库A", "描述"));

        assertEquals(1L, result.data().id());
        verify(knowledgeBaseService).create("知识库A", "描述", 1L);
    }

    @Test
    @DisplayName("B2 findById → 不存在（或无归属权）抛 404")
    void findById_notFound_throws404() {
        when(knowledgeBaseService.findById(99L, 7L, "TEACHER")).thenReturn(null);

        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> controller.findById(request("TEACHER", 7L), 99L));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertEquals("知识库不存在", ex.getReason());
    }

    @Test
    @DisplayName("B2 findById → 存在时返回 VO")
    void findById_returnsVO() {
        when(knowledgeBaseService.findById(1L, 7L, "TEACHER")).thenReturn(kb(1L));

        ApiResponse<KnowledgeBaseVO> result = controller.findById(request("TEACHER", 7L), 1L);

        assertEquals("知识库1", result.data().name());
    }

    @Test
    @DisplayName("B3 findPage → 透传 userId/role 返回分页 VO")
    void findPage_returnsPaged() {
        Page<KnowledgeBaseVO> paged = new Page<>(1, 20);
        paged.setRecords(List.of(kb(1L)));
        paged.setTotal(1);
        when(knowledgeBaseService.findPage(1, 20, "知识", 7L, "TEACHER")).thenReturn(paged);

        ApiResponse<PageResponse<KnowledgeBaseVO>> result = controller.findPage(request("TEACHER", 7L), 1, 20, "知识");

        assertEquals(1, result.data().records().size());
        verify(knowledgeBaseService).findPage(1, 20, "知识", 7L, "TEACHER");
    }

    @Test
    @DisplayName("B4 update → 教师透传 isAdmin=false")
    void update_teacher_passesAdminFlag() {
        controller.update(request("TEACHER", 7L), 1L, new KnowledgeBaseRequest("新名", "新描述"));

        verify(knowledgeBaseService).update(1L, "新名", "新描述", 7L, false);
    }

    @Test
    @DisplayName("B5 delete → 超管透传 isAdmin=true")
    void delete_superAdmin_passesAdminFlag() {
        controller.delete(request("SUPER_ADMIN", 1L), 1L);

        verify(knowledgeBaseService).delete(1L, 1L, true);
    }
}
