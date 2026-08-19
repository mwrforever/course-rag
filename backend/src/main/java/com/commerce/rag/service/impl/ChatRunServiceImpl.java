package com.commerce.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.commerce.rag.convert.ChatRunConverter;
import com.commerce.rag.entity.ChatRun;
import com.commerce.rag.exception.ConcurrentRunException;
import com.commerce.rag.mapper.ChatRunMapper;
import com.commerce.rag.service.IChatRunService;
import com.commerce.rag.vo.ChatRunVO;
import java.time.LocalDateTime;
import java.util.List;
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
public class ChatRunServiceImpl extends ServiceImpl<ChatRunMapper, ChatRun> implements IChatRunService {

    private static final Logger log = LoggerFactory.getLogger(IChatRunService.class);

    private final ChatRunMapper runMapper;
    /** Run 转换器 —— 实体 → 视图对象（entity 不出 service 边界） */
    private final ChatRunConverter chatRunConverter;

    /**
     * 创建 Run（status=QUEUED）
     *
     * <p>利用 DB partial unique index 做并发守卫：
     * 如果同一 session 已有 QUEUED 或 ACTIVE 的 run，INSERT 会冲突，
     * 抛出 {@link ConcurrentRunException}。
     *
     * @param sessionId 会话 ID
     * @param userId    用户 ID
     * @return 已持久化的 Run 视图对象（含雪花 ID）
     * @throws ConcurrentRunException 同一 session 已有活跃 run
     */
    public ChatRunVO createRun(Long sessionId, Long userId) {
        ChatRun run = new ChatRun();
        run.setSessionId(sessionId);
        run.setUserId(userId);
        run.setStatus("QUEUED");
        run.setModelCalls(0);
        run.setMetaJson("{}");
        try {
            runMapper.insert(run);
            log.info("创建 Run: runId={}, sessionId={}", run.getId(), sessionId);
            // 实体 → VO：entity 不出 service 边界（B1 清理）
            return chatRunConverter.toVO(run);
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
     * @return Run 视图对象，不存在则返回 null
     */
    public ChatRunVO findById(Long runId) {
        ChatRun run = runMapper.selectById(runId);
        // 实体 → VO：entity 不出 service 边界（B1 清理）
        return run == null ? null : chatRunConverter.toVO(run);
    }

    /**
     * 落库本次输入附件（业务入口表，spec §5.1 双存决策）
     *
     * <p>本 service 主表走内置链式（this.lambdaUpdate），按 runId 选中并仅更新 attachmentsJson 列。
     *
     * @param runId           Run ID
     * @param attachmentsJson 附件 JSON 数组字符串（"[]"=无附件；非法 JSON 已由调用方归一）
     */
    @Override
    public void updateAttachments(Long runId, String attachmentsJson) {
        log.info("落库附件: runId={}, attachmentsJson={}", runId, attachmentsJson);
        this.lambdaUpdate()
                .eq(ChatRun::getId, runId)
                .set(ChatRun::getAttachmentsJson, attachmentsJson)
                .update();
    }

    /**
     * 查询超时未结束的 ACTIVE run（M-8 巡检用）
     *
     * <p>本 service 主表查询走内置链式（this.lambdaQuery），按需取列仅 id/status。
     *
     * @param startedBefore started_at 早于该时间的 ACTIVE run（视为超时）
     * @return 超时 run 的视图对象列表
     */
    public List<ChatRunVO> findStaleActive(LocalDateTime startedBefore) {
        return runMapper
                .selectList(Wrappers.<ChatRun>lambdaQuery()
                        .select(ChatRun::getId, ChatRun::getStatus)
                        .eq(ChatRun::getStatus, "ACTIVE")
                        .isNotNull(ChatRun::getStartedAt)
                        .lt(ChatRun::getStartedAt, startedBefore))
                .stream()
                .map(chatRunConverter::toVO)
                .toList();
    }
}
