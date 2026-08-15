package com.commerce.rag.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.commerce.rag.entity.ChatRun;
import com.commerce.rag.mapper.ChatRunMapper;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Run 生命周期服务 —— 管理 chat_run 表的状态流转
 *
 * <p>核心机制：利用 DB partial unique index（uniq_active_run_per_session）
 * 做并发守卫 —— 同一 session 同时只能有一个 QUEUED 或 ACTIVE 的 run。
 * INSERT 冲突时抛出 {@link ConcurrentRunException}。
 *
 * <p>状态流转：QUEUED → ACTIVE → COMPLETED / CANCELLED / ERROR
 *
 * <p>依赖注入：Lombok @RequiredArgsConstructor 构造器注入（private final ChatRunMapper runMapper）。
 *
 * @author commerce-rag
 */
@Service
@RequiredArgsConstructor
public class ChatRunService {

    private static final Logger log = LoggerFactory.getLogger(ChatRunService.class);

    private final ChatRunMapper runMapper;

    /**
     * 创建 Run（status=QUEUED）
     *
     * <p>利用 DB partial unique index 做并发守卫：
     * 如果同一 session 已有 QUEUED 或 ACTIVE 的 run，INSERT 会冲突，
     * 抛出 {@link ConcurrentRunException}。
     *
     * @param sessionId 会话 ID
     * @param userId    用户 ID
     * @return 已持久化的 Run 实体（含雪花 ID）
     * @throws ConcurrentRunException 同一 session 已有活跃 run
     */
    public ChatRun createRun(Long sessionId, Long userId) {
        ChatRun run = new ChatRun();
        run.setSessionId(sessionId);
        run.setUserId(userId);
        run.setStatus("QUEUED");
        run.setModelCalls(0);
        run.setMetaJson("{}");
        try {
            runMapper.insert(run);
            log.info("创建 Run: runId={}, sessionId={}", run.getId(), sessionId);
            return run;
        } catch (DataIntegrityViolationException e) {
            log.warn("并发 Run 冲突: sessionId={}", sessionId);
            throw new ConcurrentRunException("会话 " + sessionId + " 已有活跃的 Run，无法创建新 Run", e);
        }
    }

    /**
     * 更新 Run 状态，自动设置 startedAt / endedAt
     *
     * @param runId  Run ID
     * @param status 新状态：ACTIVE / COMPLETED / CANCELLED / ERROR
     */
    public void updateStatus(Long runId, String status) {
        log.info("更新 Run 状态: runId={}, status={}", runId, status);
        // 合规：Wrappers 静态工厂 + lambda 链式（宪法「Wrapper 一律 lambda 链式构建，禁止 new」）
        LambdaUpdateWrapper<ChatRun> wrapper =
                Wrappers.<ChatRun>lambdaUpdate().eq(ChatRun::getId, runId).set(ChatRun::getStatus, status);
        if ("ACTIVE".equals(status)) {
            wrapper.set(ChatRun::getStartedAt, LocalDateTime.now());
        } else if ("COMPLETED".equals(status) || "CANCELLED".equals(status) || "ERROR".equals(status)) {
            wrapper.set(ChatRun::getEndedAt, LocalDateTime.now());
        }
        runMapper.update(null, wrapper);
    }

    /**
     * 根据 ID 查询 Run
     *
     * @param runId Run ID
     * @return Run 实体，不存在则返回 null
     */
    public ChatRun findById(Long runId) {
        return runMapper.selectById(runId);
    }
}
