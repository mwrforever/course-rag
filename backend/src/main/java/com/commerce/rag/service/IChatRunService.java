package com.commerce.rag.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.commerce.rag.entity.ChatRun;
import com.commerce.rag.record.AttachmentRecord;
import com.commerce.rag.vo.ChatRunStatusVO;
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
     * 更新 Run 状态（BUG-01 状态机条件守卫），自动设置 startedAt / endedAt
     *
     * <p>守卫语义（UPDATE WHERE 条件原子判定）：
     * <ul>
     *   <li>目标 ACTIVE：仅允许自 QUEUED 迁移——迟到队列任务不得复活已被巡检置 ERROR 等终态的 run</li>
     *   <li>目标终态（COMPLETED/CANCELLED/ERROR）：仅允许自 QUEUED/ACTIVE 迁移——终态 run 不可再被改写</li>
     * </ul>
     *
     * @param runId  Run ID
     * @param status 新状态：ACTIVE / COMPLETED / CANCELLED / ERROR
     * @return 影响行数：1=迁移成功；0=守卫拒绝（run 已离开迁移前提状态）或 run 不存在，
     *         调用方据此短路（如 worker 跳过图执行）
     * @throws IllegalArgumentException status 非状态机已知状态
     */
    int updateStatus(Long runId, String status);

    /**
     * 以期望状态为前提的 CAS 式置 ERROR（BUG-01 巡检 TOCTOU 修复）
     *
     * <p>巡检路径 SELECT→UPDATE 窗口内 run 状态可能已迁移（如滞留 QUEUED 的 run 恰被 worker
     * 取出转 ACTIVE 开始执行）：无条件 UPDATE 会误杀执行中的 run（置 ERROR 解锁会话 → 新 run
     * 与仍在执行的旧 run 同 thread_id 真并发）。本方法把 SELECT 时观察到的状态作为 UPDATE 前提
     * 原子判定，窗口内已迁移的 run 不受影响（返回 0 行，调用方跳过）；主路径（滞留 QUEUED/ACTIVE
     * 置 ERROR 解锁会话）行为不变，仍受 uniq_active_run_per_session 唯一索引保护。
     *
     * @param runId          Run ID
     * @param expectedStatus 期望当前状态（调用方 SELECT 时观察到的值：QUEUED / ACTIVE）
     * @return 影响行数：1=置 ERROR 成功；0=run 已不在期望状态（或不存在），调用方应跳过
     */
    int markErrorIfCurrent(Long runId, String expectedStatus);

    /**
     * 根据 ID 查询 Run
     *
     * @param runId Run ID
     * @return Run 视图对象，不存在则返回 null
     */
    ChatRunVO findById(Long runId);

    /**
     * 落库本次输入附件（业务入口表，spec §5.1 双存决策）
     *
     * <p>ChatRequest 携带的附件记录列表（JSON 数组字符串）写入 chat_run.attachments_json。
     * 由 ChatRequestWorker 在 run 进入 ACTIVE 后调用；非法 JSON 已由调用方按空数组 "[]" 归一。
     *
     * @param runId            Run ID
     * @param attachmentsJson  附件 JSON 数组字符串（"[]"=无附件）
     */
    void updateAttachments(Long runId, String attachmentsJson);

    /**
     * 查会话最近 run 的附件（后续轮次重建入口，spec §5.1）
     *
     * <p>第二轮起用户不再上传附件时，worker 以此为入口重建 AttachmentContext：
     * 查该 session 最近 limit 个 run 的 attachments_json（排除当前 run），按 url 去重
     * （同 url 只保留最近 run 的一条），JSON 解析失败的单个 run 跳过，无则返回空列表。
     *
     * @param sessionId    会话 ID
     * @param excludeRunId 排除的 run（当前 run —— 附件已在本次处理）
     * @param limit        最多查几个 run（默认 3）
     * @return 附件记录列表（去重：同 url 只保留一条；无则空列表）
     */
    List<AttachmentRecord> findRecentAttachments(Long sessionId, Long excludeRunId, int limit);

    /**
     * 查会话内可见（终态）run 的状态列表（M4 历史回显两步查询第一步）
     *
     * <p>D4 口径：历史回显保留 COMPLETED/CANCELLED/ERROR 三态 run 的非 USER 行
     * （取消/失败半截回答全量保留 + 未完成徽标）；QUEUED/ACTIVE run 行仍不进历史
     * （进行中内容靠续流路径呈现，与 D3 一致）。
     *
     * @param sessionId 会话 ID（须已通过归属校验）
     * @return 终态 run 状态列表（runId/status/errorMessage 三列投影；无则空列表）
     */
    List<ChatRunStatusVO> findVisibleRunStatuses(Long sessionId);

    /**
     * 查询滞留的 ACTIVE/QUEUED run（M-8 巡检 + B2-3 QUEUED 扩展）
     *
     * <p>进程崩溃/runPool 拒绝后 run 可能滞留 ACTIVE；附件处理窗口内崩溃或停机丢弃
     * 排队任务会滞留 QUEUED（两者均占据 uniq_active_run_per_session 使该会话后续对话
     * 恒 409）——由巡检定时任务扫描并置 ERROR 解锁。
     *
     * @param startedBefore started_at 早于该时间的 ACTIVE run（视为超时）
     * @param queuedBefore  created_at 早于该时间的 QUEUED run（视为滞留，B2-3）
     * @return 滞留 run 的视图对象列表（仅 id/status）
     */
    List<ChatRunVO> findStaleActive(LocalDateTime startedBefore, LocalDateTime queuedBefore);

    /**
     * 判断会话是否存在活跃 run（R3 删除接口 409 守卫）
     *
     * <p>活跃定义与 uniq_active_run_per_session 部分唯一索引一致：
     * status ∈ {QUEUED, ACTIVE}。会话删除前由 controller 调用，
     * 返回 true 时应阻断删除（会话正在对话中）。
     *
     * @param sessionId 会话 ID（须已通过归属校验）
     * @return true=存在 QUEUED/ACTIVE run；false=仅剩终态 run（COMPLETED/CANCELLED/ERROR）或无 run
     */
    boolean existsActiveRun(Long sessionId);
}
