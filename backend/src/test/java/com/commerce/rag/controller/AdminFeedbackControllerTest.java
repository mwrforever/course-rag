package com.commerce.rag.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.controller.dto.ApiResponse;
import com.commerce.rag.controller.dto.PageResponse;
import com.commerce.rag.service.IUserFeedbackService;
import com.commerce.rag.vo.UserFeedbackVO;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AdminFeedbackController 单元测试 —— 反馈管理端点 I1-I3
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminFeedbackController 反馈管理端点测试")
class AdminFeedbackControllerTest {

    @Mock
    private IUserFeedbackService feedbackService;

    private AdminFeedbackController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminFeedbackController(feedbackService);
    }

    @Test
    @DisplayName("I1 findPage → 透传意图筛选返回分页反馈")
    void findPage_returnsPaged() {
        UserFeedbackVO vo = new UserFeedbackVO(1L, 1L, 2L, 5L, true, "knowledge_question", LocalDateTime.now());
        Page<UserFeedbackVO> paged = new Page<>(1, 20);
        paged.setRecords(List.of(vo));
        paged.setTotal(1);
        when(feedbackService.findPage(1, 20, "knowledge_question")).thenReturn(paged);

        ApiResponse<PageResponse<UserFeedbackVO>> result = controller.findPage(1, 20, "knowledge_question");

        assertEquals(1, result.data().records().size());
        assertEquals(5L, result.data().records().get(0).userId());
    }

    @Test
    @DisplayName("I1 findPage → 不传意图时透传 null（全局统计）")
    void findPage_noIntent_passesNull() {
        Page<UserFeedbackVO> paged = new Page<>(1, 20);
        when(feedbackService.findPage(1, 20, null)).thenReturn(paged);

        controller.findPage(1, 20, null);

        verify(feedbackService).findPage(1, 20, null);
    }

    @Test
    @DisplayName("I2 findStats → 返回按意图分组的赞踩统计")
    void findStats_returnsStats() {
        when(feedbackService.findStats())
                .thenReturn(List.of(Map.of("intentType", "knowledge_question", "likeCount", 3)));

        ApiResponse<List<Map<String, Object>>> result = controller.findStats();

        assertEquals(1, result.data().size());
        assertEquals("knowledge_question", result.data().get(0).get("intentType"));
    }

    @Test
    @DisplayName("I3 delete → 携带操作者 ID 调用删除")
    void delete_passesOperatorId() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(9L);

        controller.delete(req, 1L);

        verify(feedbackService).delete(1L, 9L);
    }
}
