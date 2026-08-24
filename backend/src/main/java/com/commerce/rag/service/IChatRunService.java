package com.commerce.rag.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.commerce.rag.entity.ChatRun;
import com.commerce.rag.record.AttachmentRecord;
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
     * 查会话内已完成 run 的 ID 列表（R1 学生历史消息两步查询第一步）
     *
     * <p>M3 处置：历史回显仅保留 USER 行与 COMPLETED run 的非 USER 行，
     * 取消/异常 run 的半截 assistant 内容（thinking/工具行/正文）剔除，
     * 与实时对话「已停止生成」标注语义一致。
     *
     * @param sessionId 会话 ID（须已通过归属校验）
     * @return COMPLETED 状态的 runId 列表（无则为空列表）
     */
    List<Long> findCompletedRunIds(Long sessionId);

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
