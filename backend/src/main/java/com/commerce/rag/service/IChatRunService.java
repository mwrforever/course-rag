package com.commerce.rag.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.commerce.rag.entity.ChatRun;
import com.commerce.rag.vo.ChatRunVO;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Run 生命周期服务接口 —— 管理 chat_run 表的状态流转（主表 ChatRun）
 *
 * <p>并发守卫：DB partial unique index（uniq_active_run_per_session），
 * 冲突时抛 {@link com.commerce.rag.exception.ConcurrentRunException}。
 *
 * @author commerce-rag
 */
public interface IChatRunService extends IService<ChatRun> {

    /**
     * 创建 Run（status=QUEUED）
     *
     * @param sessionId 会话 ID
     * @param userId    用户 ID
     * @return 已持久化的 Run 视图对象（含雪花 ID，entity 不出 service 边界）
     * @throws com.commerce.rag.exception.ConcurrentRunException 同一 session 已有活跃 run
     */
    ChatRunVO createRun(Long sessionId, Long userId);

    /**
     * 更新 Run 状态，自动设置 startedAt / endedAt
     *
     * @param runId  Run ID
     * @param status 新状态：ACTIVE / COMPLETED / CANCELLED / ERROR
     */
    void updateStatus(Long runId, String status);

    /**
     * 根据 ID 查询 Run
     *
     * @param runId Run ID
     * @return Run 视图对象，不存在则返回 null
     */
    ChatRunVO findById(Long runId);

    /**
     * 查询超时未结束的 ACTIVE run（M-8 巡检用）
     *
     * <p>进程崩溃/runPool 拒绝后 run 可能滞留 ACTIVE，uniq_active_run_per_session
     * 使该会话后续对话恒 409——由巡检定时任务扫描并置 ERROR 解锁。
     *
     * @param startedBefore started_at 早于该时间的 ACTIVE run（视为超时）
     * @return 超时 run 的视图对象列表（仅 id/status）
     */
    List<ChatRunVO> findStaleActive(LocalDateTime startedBefore);
}
