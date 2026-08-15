package com.commerce.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.controller.vo.UserFeedbackVO;
import com.commerce.rag.entity.UserFeedback;
import com.commerce.rag.mapper.UserFeedbackMapper;
import com.github.benmanes.caffeine.cache.Cache;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
@RequiredArgsConstructor
public class UserFeedbackService {

    private static final Logger log = LoggerFactory.getLogger(UserFeedbackService.class);

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final UserFeedbackMapper feedbackMapper;

    /** 用户反馈转换器 —— Entity 出 service 边界前转 VO */
    private final UserFeedbackConverter feedbackConverter;

    /** Dashboard 统计缓存（TTL 60 秒；反馈增删改后失效，先写 DB 后失效——一致性铁律） */
    @Qualifier("dashboardStatsCache")
    private final Cache<String, Object> dashboardStatsCache;

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
        LambdaQueryWrapper<UserFeedback> wrapper = Wrappers.<UserFeedback>lambdaQuery()
                .eq(UserFeedback::getUserId, userId)
                .eq(UserFeedback::getMessageId, messageId);
        UserFeedback existing = feedbackMapper.selectOne(wrapper);

        if (existing != null) {
            // 更新已有反馈
            LambdaUpdateWrapper<UserFeedback> updateWrapper = Wrappers.<UserFeedback>lambdaUpdate()
                    .eq(UserFeedback::getId, existing.getId())
                    .set(UserFeedback::getIsLiked, isLiked)
                    .set(UserFeedback::getIntentType, intentType);
            feedbackMapper.update(null, updateWrapper);
            existing.setIsLiked(isLiked);
            existing.setIntentType(intentType);
            // 统计失效：点赞状态已变更（先写 DB 后失效，一致性铁律）
            dashboardStatsCache.invalidateAll();
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
        // 统计失效：反馈数已变更（先写 DB 后失效，一致性铁律）
        dashboardStatsCache.invalidateAll();
        log.info(
                "创建反馈: feedbackId={}, userId={}, messageId={}, isLiked={}",
                feedback.getId(),
                userId,
                messageId,
                isLiked);
        return feedback;
    }

    /**
     * 分页查询反馈（2026-08-15 用户裁决：教师仅见自己创建学生的反馈，超管不限制）
     *
     * @param page       页码（1-based）
     * @param size       每页条数
     * @param intentType 意图类型筛选（可选）
     * @param createdBy  教师用户 ID（null=全部，超管视角）
     * @return 分页结果（records 为用户反馈视图对象）
     */
    public IPage<UserFeedbackVO> findPage(int page, int size, String intentType, Long createdBy) {
        Page<UserFeedback> pageObj = new Page<>(page, size > 0 ? size : DEFAULT_PAGE_SIZE);
        IPage<UserFeedback> entityPage;
        if (createdBy != null) {
            // 教师隔离：user_id IN 子查询（mapper XML），仅见自己创建学生的反馈
            entityPage = feedbackMapper.selectPageFilteredByTeacher(pageObj, intentType, createdBy);
        } else {
            LambdaQueryWrapper<UserFeedback> wrapper =
                    Wrappers.<UserFeedback>lambdaQuery().orderByDesc(UserFeedback::getCreatedAt);
            if (intentType != null && !intentType.isBlank()) {
                wrapper.eq(UserFeedback::getIntentType, intentType);
            }
            entityPage = feedbackMapper.selectPage(pageObj, wrapper);
        }
        // 实体分页 → VO 分页：records 逐条转换，total/current/size 分页语义保持
        Page<UserFeedbackVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(
                entityPage.getRecords().stream().map(feedbackConverter::toVO).toList());
        return voPage;
    }

    /**
     * 按意图分组统计赞/踩数
     *
     * <p>perf P3-1：单条 GROUP BY 聚合 SQL（mapper XML）替代「先查意图列表 + 逐类 2 次 count」
     * 的 1+2N 次查询——意图类型随业务扩展不线性放大查询数。
     *
     * @return 统计列表，每项包含 intentType, likedCount, dislikedCount
     */
    public List<Map<String, Object>> findStats(Long createdBy) {
        List<Map<String, Object>> rows = feedbackMapper.selectIntentStats(createdBy);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> stat = new HashMap<>();
            stat.put("intentType", row.get("intent_type"));
            stat.put("likedCount", row.get("liked_count") != null ? ((Number) row.get("liked_count")).longValue() : 0L);
            stat.put(
                    "dislikedCount",
                    row.get("disliked_count") != null ? ((Number) row.get("disliked_count")).longValue() : 0L);
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
        LambdaUpdateWrapper<UserFeedback> wrapper = Wrappers.<UserFeedback>lambdaUpdate()
                .eq(UserFeedback::getId, id)
                .set(UserFeedback::getDeleted, System.currentTimeMillis());
        feedbackMapper.update(null, wrapper);
        // 统计失效：反馈已删除（先写 DB 后失效，一致性铁律）
        dashboardStatsCache.invalidateAll();
        log.info("删除反馈: feedbackId={}, operatorId={}", id, operatorId);
    }
}
