package com.commerce.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.entity.UserFeedback;
import com.commerce.rag.mapper.UserFeedbackMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 用户反馈服务 —— 封装 user_feedback 表的 CRUD + 统计
 *
 * <p>is_liked 三态：NULL（未评）/ TRUE（赞）/ FALSE（踩）。
 * UNIQUE(user_id, message_id) 约束：同一用户同一消息只允许一条反馈。
 * 纯统计无闭环。
 *
 * @author commerce-rag
 */
@Service
public class UserFeedbackService {

    private static final Logger log = LoggerFactory.getLogger(UserFeedbackService.class);

    private static final int DEFAULT_PAGE_SIZE = 20;

    @Autowired
    private UserFeedbackMapper feedbackMapper;

    /**
     * 创建反馈（或更新已有反馈）
     *
     * <p>UNIQUE(user_id, message_id) 约束：同一用户同一消息只允许一条反馈。
     * user_id 取自当前登录用户（P0-2h：防止跨用户伪造赞踩）。
     *
     * @param userId     反馈用户 ID（当前登录用户）
     * @param sessionId  会话 ID
     * @param messageId  消息 ID
     * @param isLiked    是否点赞（NULL/TRUE/FALSE）
     * @param intentType 意图类型
     * @return 已持久化的反馈实体
     */
    public UserFeedback create(Long userId, Long sessionId, Long messageId, Boolean isLiked, String intentType) {
        // 查询是否已有该用户的反馈（按 user_id + message_id 唯一定位）
        LambdaQueryWrapper<UserFeedback> wrapper = new LambdaQueryWrapper<UserFeedback>()
                .eq(UserFeedback::getUserId, userId)
                .eq(UserFeedback::getMessageId, messageId);
        UserFeedback existing = feedbackMapper.selectOne(wrapper);

        if (existing != null) {
            // 更新已有反馈
            LambdaUpdateWrapper<UserFeedback> updateWrapper = new LambdaUpdateWrapper<UserFeedback>()
                    .eq(UserFeedback::getId, existing.getId())
                    .set(UserFeedback::getIsLiked, isLiked)
                    .set(UserFeedback::getIntentType, intentType);
            feedbackMapper.update(null, updateWrapper);
            existing.setIsLiked(isLiked);
            existing.setIntentType(intentType);
            log.info("更新反馈: feedbackId={}, isLiked={}", existing.getId(), isLiked);
            return existing;
        }

        // 创建新反馈
        UserFeedback feedback = new UserFeedback();
        feedback.setUserId(userId);
        feedback.setSessionId(sessionId);
        feedback.setMessageId(messageId);
        feedback.setIsLiked(isLiked);
        feedback.setIntentType(intentType);
        feedbackMapper.insert(feedback);
        log.info(
                "创建反馈: feedbackId={}, userId={}, messageId={}, isLiked={}",
                feedback.getId(),
                userId,
                messageId,
                isLiked);
        return feedback;
    }

    /**
     * 分页查询反馈
     *
     * @param page       页码（1-based）
     * @param size       每页条数
     * @param intentType 意图类型筛选（可选）
     * @return 分页结果
     */
    public IPage<UserFeedback> findPage(int page, int size, String intentType) {
        Page<UserFeedback> pageObj = new Page<>(page, size > 0 ? size : DEFAULT_PAGE_SIZE);
        LambdaQueryWrapper<UserFeedback> wrapper =
                new LambdaQueryWrapper<UserFeedback>().orderByDesc(UserFeedback::getCreatedAt);
        if (intentType != null && !intentType.isBlank()) {
            wrapper.eq(UserFeedback::getIntentType, intentType);
        }
        return feedbackMapper.selectPage(pageObj, wrapper);
    }

    /**
     * 按意图分组统计赞/踩数
     *
     * @return 统计列表，每项包含 intentType, likedCount, dislikedCount
     */
    public List<Map<String, Object>> findStats() {
        // 查询所有不重复的 intent_type
        LambdaQueryWrapper<UserFeedback> typeWrapper = new LambdaQueryWrapper<UserFeedback>()
                .select(UserFeedback::getIntentType)
                .groupBy(UserFeedback::getIntentType);
        List<UserFeedback> types = feedbackMapper.selectList(typeWrapper);

        List<Map<String, Object>> result = new ArrayList<>();
        for (UserFeedback type : types) {
            String intentType = type.getIntentType();
            if (intentType == null) continue;

            // 统计赞数
            Long likedCount = feedbackMapper.selectCount(new LambdaQueryWrapper<UserFeedback>()
                    .eq(UserFeedback::getIntentType, intentType)
                    .eq(UserFeedback::getIsLiked, true));

            // 统计踩数
            Long dislikedCount = feedbackMapper.selectCount(new LambdaQueryWrapper<UserFeedback>()
                    .eq(UserFeedback::getIntentType, intentType)
                    .eq(UserFeedback::getIsLiked, false));

            Map<String, Object> stat = new HashMap<>();
            stat.put("intentType", intentType);
            stat.put("likedCount", likedCount);
            stat.put("dislikedCount", dislikedCount);
            result.add(stat);
        }
        return result;
    }

    /**
     * 删除反馈（软删除，写入毫秒时间戳）
     *
     * @param id         反馈 ID
     * @param operatorId 操作者 ID（用于审计日志）
     */
    public void delete(Long id, Long operatorId) {
        LambdaUpdateWrapper<UserFeedback> wrapper = new LambdaUpdateWrapper<UserFeedback>()
                .eq(UserFeedback::getId, id)
                .set(UserFeedback::getDeleted, System.currentTimeMillis());
        feedbackMapper.update(null, wrapper);
        log.info("删除反馈: feedbackId={}, operatorId={}", id, operatorId);
    }
}
