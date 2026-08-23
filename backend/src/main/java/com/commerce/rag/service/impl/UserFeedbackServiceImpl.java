package com.commerce.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.commerce.rag.convert.UserFeedbackConverter;
import com.commerce.rag.entity.UserFeedback;
import com.commerce.rag.mapper.UserFeedbackMapper;
import com.commerce.rag.service.IUserFeedbackService;
import com.commerce.rag.vo.UserFeedbackVO;
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
public class UserFeedbackServiceImpl extends ServiceImpl<UserFeedbackMapper, UserFeedback>
        implements IUserFeedbackService {

    private static final Logger log = LoggerFactory.getLogger(IUserFeedbackService.class);

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final UserFeedbackMapper feedbackMapper;

    /**
     * 雪花 ID 生成器（MP 自动装配的 IdentifierGenerator）
     *
     * <p>P1-5：upsert 走自定义 XML SQL，ASSIGN_ID 的自动填充仅在 BaseMapper.insert 生效，
     * 故 service 显式取号（与 ASSIGN_ID 同源同策略，非手动 IdWorker）；
     * 冲突路径该 ID 被丢弃，以 RETURNING 返回的既有行 id 为准。
     */
    private final IdentifierGenerator identifierGenerator;

    /** 用户反馈转换器 —— Entity 出 service 边界前转 VO */
    private final UserFeedbackConverter feedbackConverter;

    /** Dashboard 统计缓存（TTL 60 秒；反馈增删改后失效，先写 DB 后失效——一致性铁律） */
    @Qualifier("dashboardStatsCache")
    private final Cache<String, Object> dashboardStatsCache;

    /**
     * 创建反馈（或更新已有反馈）
     *
     * <p>P1-5：单条 upsert SQL（ON CONFLICT (user_id, message_id) WHERE deleted = 0），
     * 替代原 selectOne + insert/update 两往返；并发双击从撞唯一索引异常收敛为幂等更新。
     * 返回值语义与原实现一致：插入=新行状态，冲突=既有行身份（id/sessionId）+ 本次赞踩与意图。
     * user_id 取自当前登录用户（P0-2h：防止跨用户伪造赞踩）。
     *
     * @param userId     反馈用户 ID（当前登录用户）
     * @param sessionId  会话 ID（冲突路径不覆盖既有行 session_id）
     * @param messageId  消息 ID
     * @param isLiked    是否点赞（NULL/TRUE/FALSE）
     * @param intentType 意图类型
     * @return 已持久化的反馈视图对象（实体不出 service 边界）
     */
    public UserFeedbackVO create(Long userId, Long sessionId, Long messageId, Boolean isLiked, String intentType) {
        UserFeedback feedback = new UserFeedback();
        feedback.setUserId(userId);
        feedback.setSessionId(sessionId);
        feedback.setMessageId(messageId);
        feedback.setIsLiked(isLiked);
        feedback.setIntentType(intentType);
        // 自定义 upsert SQL 需显式生成雪花 ID（冲突路径被 RETURNING 的既有行 id 覆盖）
        feedback.setId(identifierGenerator.nextId(feedback).longValue());

        // 单条 upsert：不存在→插入新行；存在（deleted=0）→幂等更新赞踩与意图；软删行→插新行
        UserFeedback saved = feedbackMapper.upsertFeedback(feedback);
        // 统计失效：反馈已写入（先写 DB 后失效，一致性铁律——时机与原实现一致）
        dashboardStatsCache.invalidateAll();
        log.info(
                "写入反馈（upsert）: feedbackId={}, userId={}, messageId={}, isLiked={}",
                saved.getId(),
                userId,
                messageId,
                isLiked);
        return feedbackConverter.toVO(saved);
    }

    /**
     * 分页查询反馈（2026-08-15 用户裁决：全局可见，不区分教师/超管视角）
     *
     * @param page       页码（1-based）
     * @param size       每页条数
     * @param intentType 意图类型筛选（可选）
     * @return 分页结果（records 为用户反馈视图对象，按创建时间降序）
     */
    public IPage<UserFeedbackVO> findPage(int page, int size, String intentType) {
        Page<UserFeedback> pageObj = new Page<>(page, size > 0 ? size : DEFAULT_PAGE_SIZE);
        LambdaQueryWrapper<UserFeedback> wrapper =
                Wrappers.<UserFeedback>lambdaQuery().orderByDesc(UserFeedback::getCreatedAt);
        if (intentType != null && !intentType.isBlank()) {
            wrapper.eq(UserFeedback::getIntentType, intentType);
        }
        IPage<UserFeedback> entityPage = feedbackMapper.selectPage(pageObj, wrapper);
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
    public List<Map<String, Object>> findStats() {
        List<Map<String, Object>> rows = feedbackMapper.selectIntentStats();
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
