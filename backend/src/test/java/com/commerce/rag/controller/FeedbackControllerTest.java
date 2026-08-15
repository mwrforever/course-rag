package com.commerce.rag.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.controller.dto.ApiResponse;
import com.commerce.rag.controller.dto.FeedbackRequest;
import com.commerce.rag.controller.vo.UserFeedbackVO;
import com.commerce.rag.entity.UserFeedback;
import com.commerce.rag.service.UserFeedbackConverter;
import com.commerce.rag.service.UserFeedbackService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * FeedbackController 单元测试 —— C 端反馈端点 J5
 *
 * <p>关键断言：user_id 取自当前登录用户而非请求体，防止跨用户伪造。
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FeedbackController 学生反馈端点测试")
class FeedbackControllerTest {

    @Mock
    private UserFeedbackService feedbackService;

    @Mock
    private UserFeedbackConverter converter;

    private FeedbackController controller;

    @BeforeEach
    void setUp() {
        controller = new FeedbackController(feedbackService, converter);
    }

    @Test
    @DisplayName("J5 create → 以登录用户身份提交反馈并返回记录（VO）")
    void create_usesLoginUserAndReturnsFeedback() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(5L);
        FeedbackRequest feedbackRequest = new FeedbackRequest(1L, 2L, true, "knowledge_question");

        UserFeedback feedback = new UserFeedback();
        feedback.setId(1L);
        feedback.setUserId(5L);
        feedback.setSessionId(1L);
        feedback.setMessageId(2L);
        feedback.setIsLiked(true);
        feedback.setIntentType("knowledge_question");
        feedback.setCreatedAt(LocalDateTime.now());
        when(feedbackService.create(5L, 1L, 2L, true, "knowledge_question")).thenReturn(feedback);
        when(converter.toVO(feedback))
                .thenReturn(new UserFeedbackVO(1L, 1L, 2L, 5L, true, "knowledge_question", feedback.getCreatedAt()));

        ApiResponse<UserFeedbackVO> result = controller.create(req, feedbackRequest);

        assertEquals(5L, result.data().userId());
        assertEquals(Boolean.TRUE, result.data().isLiked());
        verify(feedbackService).create(5L, 1L, 2L, true, "knowledge_question");
    }

    @Test
    @DisplayName("J5 create → 点踩（isLiked=false）同样以登录用户身份提交")
    void create_dislike_usesLoginUser() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(5L);
        FeedbackRequest feedbackRequest = new FeedbackRequest(1L, 3L, false, "chat");

        UserFeedback feedback = new UserFeedback();
        feedback.setId(2L);
        feedback.setUserId(5L);
        feedback.setIsLiked(false);
        when(feedbackService.create(5L, 1L, 3L, false, "chat")).thenReturn(feedback);
        when(converter.toVO(feedback)).thenReturn(new UserFeedbackVO(2L, 1L, 3L, 5L, false, "chat", null));

        ApiResponse<UserFeedbackVO> result = controller.create(req, feedbackRequest);

        assertEquals(Boolean.FALSE, result.data().isLiked());
    }
}
