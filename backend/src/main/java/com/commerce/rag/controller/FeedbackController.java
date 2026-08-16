package com.commerce.rag.controller;

import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.dto.ApiResponse;
import com.commerce.rag.dto.FeedbackRequest;
import com.commerce.rag.service.IUserFeedbackService;
import com.commerce.rag.vo.UserFeedbackVO;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端反馈 Controller（J5）
 *
 * <p>学生提交对 AI 回答的点赞/点踩反馈。
 * is_liked 三态：NULL/TRUE/FALSE。
 * UNIQUE(user_id, message_id) 约束：同一用户同一消息只允许一条反馈。
 *
 * @author commerce-rag
 */
@RestController
@RequestMapping("/api/v1/student/feedbacks")
@PreAuthorize("hasRole('STUDENT')")
public class FeedbackController {

    private static final Logger log = LoggerFactory.getLogger(FeedbackController.class);

    private final IUserFeedbackService feedbackService;

    public FeedbackController(IUserFeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    /** J5: 提交反馈（user_id 取当前登录用户，防止跨用户伪造） */
    @PostMapping
    public ApiResponse<UserFeedbackVO> create(
            HttpServletRequest request, @RequestBody FeedbackRequest feedbackRequest) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        return ApiResponse.ok(feedbackService.create(
                userId,
                feedbackRequest.sessionId(),
                feedbackRequest.messageId(),
                feedbackRequest.isLiked(),
                feedbackRequest.intentType()));
    }
}
