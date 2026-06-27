package com.commerce.rag.feedback;

import com.commerce.rag.common.result.ApiResult;
import com.commerce.rag.feedback.entity.FeedbackStats;
import com.commerce.rag.feedback.entity.UserFeedback;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 用户反馈 API 控制器
 *
 * 提供反馈提交、统计和低分查询接口。
 */
@RestController
@RequestMapping("/api/v1/feedback")
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    /**
     * 提交反馈
     *
     * @param request 反馈请求（sessionId, messageId, rating, comment）
     * @return 保存的反馈记录
     */
    @PostMapping
    public ApiResult<UserFeedback> submitFeedback(@RequestBody FeedbackRequest request) {
        UserFeedback feedback = feedbackService.submitFeedback(
                request.getSessionId(),
                request.getMessageId(),
                request.getRating(),
                request.getComment()
        );
        return ApiResult.ok(feedback);
    }

    /**
     * 获取反馈统计（按意图类型分组）
     *
     * @return 各模式的统计结果
     */
    @GetMapping("/stats")
    public ApiResult<List<FeedbackStats>> getStats() {
        return ApiResult.ok(feedbackService.getStats());
    }

    /**
     * 查询低分回答列表（供人工复核）
     *
     * @param limit 返回数量，默认 20
     * @return 低分反馈列表
     */
    @GetMapping("/low-rated")
    public ApiResult<List<UserFeedback>> getLowRated(
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(feedbackService.getLowRatedFeedbacks(limit));
    }

    /**
     * 反馈请求 DTO
     */
    @Data
    public static class FeedbackRequest {
        @NotNull(message = "sessionId 不能为空")
        private UUID sessionId;

        @NotNull(message = "messageId 不能为空")
        private UUID messageId;

        @NotNull(message = "评分不能为空")
        @Min(value = 1, message = "评分最小为 1")
        @Max(value = 5, message = "评分最大为 5")
        private Short rating;

        /** 用户评论（可选） */
        private String comment;
    }
}
