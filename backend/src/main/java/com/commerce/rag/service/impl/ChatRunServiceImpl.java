package com.commerce.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.commerce.rag.convert.ChatRunConverter;
import com.commerce.rag.entity.ChatRun;
import com.commerce.rag.exception.ConcurrentRunException;
import com.commerce.rag.mapper.ChatRunMapper;
import com.commerce.rag.record.AttachmentRecord;
import com.commerce.rag.service.IChatRunService;
import com.commerce.rag.vo.ChatRunVO;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
     * 查会话最近 run 的附件（后续轮次重建入口，spec §5.1 最终三表决策）
     *
     * <p>第二轮起用户不再上传附件时，worker 以此为入口重建 AttachmentContext。
     * 本 service 主表走内置链式（this.lambdaQuery），按需取列 id/attachmentsJson
     * （id 供解析失败告警定位损坏 run）。
     * 按 url 去重（同 url 只保留最近 run 的一条），单个 run JSON 解析失败跳过（warn 日志），
     * 无则返回空列表。
     *
     * @param sessionId    会话 ID
     * @param excludeRunId 排除的 run（当前 run —— 附件已在本次处理）
     * @param limit        最多查几个 run（默认 3）
     * @return 附件记录列表（去重：同 url 只保留一条；无则空列表）
     */
    @Override
    public List<AttachmentRecord> findRecentAttachments(Long sessionId, Long excludeRunId, int limit) {
        // 查该 session 最近 limit 个 run 的 id/attachments_json（排除当前 run；ID 倒序 → 最近 run 优先）
        List<ChatRun> runs = this.lambdaQuery()
                .select(ChatRun::getId, ChatRun::getAttachmentsJson)
                .eq(ChatRun::getSessionId, sessionId)
                .ne(excludeRunId != null, ChatRun::getId, excludeRunId)
                .isNotNull(ChatRun::getAttachmentsJson)
                .orderByDesc(ChatRun::getId)
                .last("LIMIT " + limit)
                .list();
        // 本方法仅承担 SQL 获取；聚合逻辑下沉 collectUniqueAttachments（纯函数可单测，规范「测试真实断言」）
        return collectUniqueAttachments(runs);
    }

    /**
     * 从最近 run 行集合聚合附件记录（纯聚合逻辑，无 DB 访问 —— 供 findRecentAttachments 调用）
     *
     * <p>按 url 去重：LinkedHashMap 保插入序 → 最近 run 先入，同 url 只保留最早出现的记录；
     * null/空白 attachmentsJson 跳过；单个 run JSON 解析失败跳过（warn 日志，不阻断整体重建）。
     *
     * @param runs 查询出的最近 run 行（调用方保证按 orderByDesc(id) 排序，最近 run 在前）
     * @return 附件记录列表（去重：同 url 只保留一条；无则空列表）
     */
    public List<AttachmentRecord> collectUniqueAttachments(List<ChatRun> runs) {
        Map<String, AttachmentRecord> unique = new LinkedHashMap<>();
        for (ChatRun run : runs) {
            if (run.getAttachmentsJson() == null || run.getAttachmentsJson().isBlank()) {
                continue;
            }
            try {
                List<AttachmentRecord> records = new Gson()
                        .fromJson(run.getAttachmentsJson(), new TypeToken<List<AttachmentRecord>>() {}.getType());
                for (AttachmentRecord r : records) {
                    unique.putIfAbsent(r.url(), r);
                }
            } catch (Exception e) {
                // 单个 run 附件 JSON 解析失败跳过，不阻断整体重建（损坏数据不扩散）
                log.warn("run 附件 JSON 解析失败，跳过: runId={}", run.getId());
            }
        }
        return new ArrayList<>(unique.values());
    }

    /**
     * 查询滞留的 ACTIVE/QUEUED run（M-8 巡检 + B2-3 QUEUED 扩展）
     *
     * <p>查询目标虽为本 service 主表，但既有实现走 runMapper + Wrappers 静态工厂
     * （or 嵌套分支链式构建），保持该写法。按需取列仅 id/status。
     * 两分支整体包在一层 and 内，避免与逻辑删除条件（deleted=0）因 OR 优先级产生
     * 「deleted=0 AND (ACTIVE 分支) OR (QUEUED 分支)」的错误展开。
     *
     * @param startedBefore started_at 早于该时间的 ACTIVE run（视为超时）
     * @param queuedBefore  created_at 早于该时间的 QUEUED run（视为滞留，B2-3：
     *                      附件处理窗口内崩溃/停机丢任务的 run 全程停留 QUEUED）
     * @return 滞留 run 的视图对象列表
     */
    public List<ChatRunVO> findStaleActive(LocalDateTime startedBefore, LocalDateTime queuedBefore) {
        return runMapper
                .selectList(Wrappers.<ChatRun>lambdaQuery()
                        .select(ChatRun::getId, ChatRun::getStatus)
                        .and(w -> w.and(a -> a.eq(ChatRun::getStatus, "ACTIVE")
                                        .isNotNull(ChatRun::getStartedAt)
                                        .lt(ChatRun::getStartedAt, startedBefore))
                                .or(q -> q.eq(ChatRun::getStatus, "QUEUED").lt(ChatRun::getCreatedAt, queuedBefore))))
                .stream()
                .map(chatRunConverter::toVO)
                .toList();
    }
}
