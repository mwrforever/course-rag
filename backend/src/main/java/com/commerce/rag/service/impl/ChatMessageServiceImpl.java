package com.commerce.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.commerce.rag.convert.ChatSessionConverter;
import com.commerce.rag.convert.StudentConverter;
import com.commerce.rag.entity.ChatMessage;
import com.commerce.rag.mapper.ChatMessageMapper;
import com.commerce.rag.record.AssistantEntitySplitter;
import com.commerce.rag.record.AttachmentRecord;
import com.commerce.rag.record.RetrievalSource;
import com.commerce.rag.service.IChatMessageService;
import com.commerce.rag.service.IChatRunService;
import com.commerce.rag.vo.ChatMessageVO;
import com.commerce.rag.vo.ChatRunStatusVO;
import com.commerce.rag.vo.StudentMessageVO;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 消息服务 —— 批量持久化与查询 chat_message
 *
 * <p>设计文档 §3.5：run 结束后一次性批量 INSERT 消息，
 * 使用 MP saveBatch（JDBC 批处理，默认 1000 条一批 flush，自动填充雪花 ID）。
 *
 * <p>单条查询和列表查询走 MyBatis-Plus Lambda 链式 API。
 *
 * @author commerce-rag
 */
@Service
@RequiredArgsConstructor
public class ChatMessageServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage> implements IChatMessageService {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageServiceImpl.class);

    /** 单页消息条数上限（防御性钳制：一轮工具调用可产出 5+ 行，禁止无界拉取） */
    private static final int MAX_PAGE_SIZE = 500;

    private final ChatMessageMapper messageMapper;
    /** 会话转换器 —— 消息视图对象转换（toMessageVO） */
    private final ChatSessionConverter chatSessionConverter;
    /** 学生转换器 —— 学生消息 VO 转换（toStudentMessageVO，sources/attachments JSON 解析） */
    private final StudentConverter studentConverter;
    /** Run 服务 —— 两步查询第一步取终态 run 状态列表（宪法：跨 service 走对方接口，禁直操作他人 mapper） */
    private final IChatRunService chatRunService;

    /**
     * 批量插入消息（run 结束后一次性写入）
     *
     * <p>使用 MP saveBatch（JDBC 批处理）高效批量插入，@TableId(ASSIGN_ID) 自动生成雪花 ID。
     * 须在事务内调用：saveBatch 非事务下按批次分段提交，事务保证整体原子性。
     *
     * @param messages 消息列表
     */
    @Transactional
    public void batchInsert(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (ChatMessage msg : messages) {
            // sourcesJson 为 JSONB 列，空值兜底为 "[]"（与原 JdbcTemplate 参数绑定语义一致）
            if (msg.getSourcesJson() == null) {
                msg.setSourcesJson("[]");
            }
        }
        this.saveBatch(messages);
        log.info("批量插入消息: count={}", messages.size());
    }

    /**
     * 用户消息行提前落库（2026-09-03 多会话并发历史可见修复）
     *
     * <p>执行流程：构造 USER 行（seq=0，attachments_json 携带本轮附件列表——spec §5.1
     * 双存决策口径与 persistMessages 原用户行一致）→ 单条 save（雪花 ID 自动填充）。
     *
     * <p>幂等防御：迟到队列重投递等场景下 (run_id, seq=0) 命中 V13 唯一索引冲突
     * （DataIntegrityViolationException）= 该行已落库，按已处理跳过（warn 记录），
     * 调用方不得重试。
     *
     * @param runId           Run ID（worker 已认领置 ACTIVE 的 run）
     * @param sessionId       会话 ID
     * @param query           用户问题原文（不加 caption 前缀）
     * @param attachmentsJson 附件 JSON 数组字符串（空/null 兜底 "[]"）
     */
    @Override
    public void saveUserMessageRow(Long runId, Long sessionId, String query, String attachmentsJson) {
        ChatMessage userMsg = new ChatMessage();
        userMsg.setSessionId(sessionId);
        userMsg.setRunId(runId);
        userMsg.setRole("USER");
        userMsg.setContent(query);
        userMsg.setSeq(0);
        userMsg.setSourcesJson("[]");
        // attachments_json：空/null 兜底 "[]"（列 NOT NULL 口径与批量插入一致）
        userMsg.setAttachmentsJson(attachmentsJson == null || attachmentsJson.isBlank() ? "[]" : attachmentsJson);
        try {
            this.save(userMsg);
            log.info("用户消息行提前落库: runId={}, sessionId={}", runId, sessionId);
        } catch (DataIntegrityViolationException e) {
            // (run_id, seq=0) 唯一索引冲突 = 已落库（迟到队列重投递等），幂等跳过不重试
            log.warn("用户消息行已落库（(run_id,seq) 唯一索引冲突），幂等跳过: runId={}", runId);
        }
    }

    /**
     * 按 run_id 查询消息（按 seq 升序），用于降级重组和前端历史回放
     *
     * <p>L-2：按需取列——仅投影 ChatMessageVO 所需 9 列（sourcesJson/tokenCount/
     * confidence/traceId/sessionId 等大字段或内部字段丢弃）。
     * 2026-08-28 时间线改版：投影补 thinking_stage（replayFromPg 降级回放据此重建
     * 带 stage 的 THINKING 事件；历史存量行该列为 null，回放输出 JSON null 不报错）。
     * 2026-08-29 消息实体化：assistant 实体行经 {@link AssistantEntitySplitter} 拆行还原
     * 事件序 VO（QU→thinking+query_plan、caption→thinking、主 agent→thinking+TOOL_CALL×N+
     * 正文，VO 形态与实体化前完全一致），非实体行（增量行/存量行）原样透传——消费面
     * （replayFromPg / resolveAssistantMessageId）零改动。
     *
     * @param runId Run ID
     * @return 消息视图对象列表（按 seq 升序，实体行已拆行还原事件序；剔除 sessionId/sourcesJson 等内部字段）
     */
    public List<ChatMessageVO> findByRunId(Long runId) {
        LambdaQueryWrapper<ChatMessage> wrapper = Wrappers.<ChatMessage>lambdaQuery()
                .select(
                        ChatMessage::getId,
                        ChatMessage::getRole,
                        ChatMessage::getContent,
                        ChatMessage::getMessageType,
                        ChatMessage::getThinkingStage,
                        ChatMessage::getIntentType,
                        ChatMessage::getRunId,
                        ChatMessage::getSeq,
                        ChatMessage::getCreatedAt)
                .eq(ChatMessage::getRunId, runId)
                .orderByAsc(ChatMessage::getSeq);
        // 实体列表 → VO 列表：assistant 实体行拆行还原事件序（同实体拆出 VO seq 倒推连续，
        // 原地展开即保持 seq 序；nullsLast 兜底防御），非实体行原样透传
        return messageMapper.selectList(wrapper).stream()
                .map(chatSessionConverter::toMessageVO)
                .flatMap(vo -> AssistantEntitySplitter.splitEntity(vo).stream())
                .sorted(Comparator.comparing(ChatMessageVO::seq, Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());
    }

    /**
     * 按 session_id 查询全部消息（按 created_at + seq 升序），用于管理端会话详情
     *
     * <p>L-2：按需取列——2026-08-29 消息实体化投影修订：仅投影实体行所需 7 列
     * （id/role/content/messageType/runId/seq/createdAt）——正常路径为 assistant 实体行
     * （content 为 spec §3.1 JSON，一行一次调用全貌，管理端查看体验升级），thinking_stage
     * 列不再需要（stage 在 content JSON 内）、intentType 亦不投影（不再依赖 thinking_stage
     * 的「与 findByRunId 同款」失实注释同步修订）；取消/错误路径增量行原样返回（管理端无
     * M3 过滤保持，看全部含取消 run）。
     * M5 排序修复：created_at 相同（同事务 saveBatch 批内）时按 seq 复合排序，消除排序不稳定。
     *
     * @param sessionId 会话 ID
     * @return 消息视图对象列表（实体行原样返回；剔除 sessionId/sourcesJson 等内部字段）
     */
    public List<ChatMessageVO> findBySessionId(Long sessionId) {
        LambdaQueryWrapper<ChatMessage> wrapper = Wrappers.<ChatMessage>lambdaQuery()
                .select(
                        ChatMessage::getId,
                        ChatMessage::getRole,
                        ChatMessage::getContent,
                        ChatMessage::getMessageType,
                        ChatMessage::getRunId,
                        ChatMessage::getSeq,
                        ChatMessage::getCreatedAt)
                .eq(ChatMessage::getSessionId, sessionId)
                // M5：复合排序（created_at 相同时按 run 内 seq 定序，批内插入顺序稳定）
                .orderByAsc(ChatMessage::getCreatedAt)
                .orderByAsc(ChatMessage::getSeq);
        // 实体列表 → VO 列表：逐条转换（实体行原样返回，不拆行——B 端一行看全貌），
        // sessionId/sourcesJson 等内部字段不随 VO 出边界
        return messageMapper.selectList(wrapper).stream()
                .map(chatSessionConverter::toMessageVO)
                .collect(Collectors.toList());
    }

    /**
     * 学生端分页查询会话历史消息（R1 补口 A）
     *
     * <p>执行流程：
     * <ol>
     *   <li>M4 终态保留口径第一步——经 IChatRunService 取会话内终态（COMPLETED/CANCELLED/ERROR）
     *       run 状态列表（取消/失败 run 的半截 assistant 内容全量保留 + 未完成徽标数据源；
     *       QUEUED/ACTIVE run 行仍不进历史，进行中内容靠续流路径呈现，与 D3 一致）</li>
     *   <li>size 上限钳制 500（防御性，宪法查询必带分页）</li>
     *   <li>M4 第二步——消息表过滤 {@code session_id=? and (role='USER' or run_id in (...))}，
     *       空 runId 列表退化为仅查 USER 行（避免生成 IN () 非法 SQL）</li>
     *   <li>排序 created_at asc + seq asc 复合，升序 page=1 即最旧一页（聊天 UI 渲染方向）</li>
     *   <li>entity 分页 → 学生 VO 分页（sources/attachments JSON 由转换器解析，entity 不出边界；
     *       run 终态/错误信息随终态行下发——前端徽标渲染依据）</li>
     * </ol>
     *
     * @param sessionId 会话 ID（调用方须先完成归属校验）
     * @param page      页码（1-based）
     * @param size      每页条数（超 500 钳制为 500）
     * @return 学生消息 VO 分页（records 含解析后的 sources/attachments 数组与 run 终态字段）
     */
    public IPage<StudentMessageVO> findStudentMessagesBySession(Long sessionId, int page, int size) {
        // M4 第一步：会话内终态 run 状态列表（经对方 service 接口，跨模块禁直操作 mapper）
        List<ChatRunStatusVO> visibleRuns = chatRunService.findVisibleRunStatuses(sessionId);
        // 终态 runId 列表（消息表 run_id IN 过滤用）
        List<Long> visibleRunIds =
                visibleRuns.stream().map(ChatRunStatusVO::runId).toList();
        // runId → 终态状态映射（VO 构造时随行下发 runStatus/errorMessage）
        Map<Long, ChatRunStatusVO> statusByRunId =
                visibleRuns.stream().collect(Collectors.toMap(ChatRunStatusVO::runId, v -> v));
        // size 上限钳制（一轮工具调用产出 5+ 行，防止无界拉取）
        Page<ChatMessage> pageObj = new Page<>(page, Math.min(size, MAX_PAGE_SIZE));
        LambdaQueryWrapper<ChatMessage> wrapper = Wrappers.<ChatMessage>lambdaQuery()
                // 按需取列：投影补 sources_json/attachments_json（服务端解析 JSON 用）+
                // thinking_stage（2026-08-28 时间线改版：thinking 行阶段键下发给前端分段渲染），丢弃内部字段
                .select(
                        ChatMessage::getId,
                        ChatMessage::getRole,
                        ChatMessage::getContent,
                        ChatMessage::getMessageType,
                        ChatMessage::getThinkingStage,
                        ChatMessage::getIntentType,
                        ChatMessage::getRunId,
                        ChatMessage::getSeq,
                        ChatMessage::getCreatedAt,
                        ChatMessage::getSourcesJson,
                        ChatMessage::getAttachmentsJson)
                .eq(ChatMessage::getSessionId, sessionId);
        if (visibleRunIds.isEmpty()) {
            // 会话内无终态 run：退化为仅保留 USER 行（用户提问痕迹完整，进行中内容靠续流呈现）
            wrapper.eq(ChatMessage::getRole, "USER");
        } else {
            // M4 核心：USER 行 + 终态 run 的非 USER 行（OR 嵌套须包一层，避免与软删条件错误展开）
            wrapper.and(w -> w.eq(ChatMessage::getRole, "USER").or().in(ChatMessage::getRunId, visibleRunIds));
        }
        // 复合排序：批内 created_at 相同时按 seq 定序（M5 同根因）
        wrapper.orderByAsc(ChatMessage::getCreatedAt).orderByAsc(ChatMessage::getSeq);
        IPage<ChatMessage> entityPage = messageMapper.selectPage(pageObj, wrapper);
        log.info(
                "查询学生历史消息: sessionId={}, page={}, rows={}, visibleRuns={}",
                sessionId,
                page,
                entityPage.getRecords().size(),
                visibleRunIds.size());
        // entity 分页 → 学生 VO 分页：分页元数据保持，records 经拆行/转换产出——
        // assistant 实体行先拆行还原事件序（消息实体化 2026-08-29），再逐 VO 携带
        // sources/attachments（解析自实体行 JSONB 列）；非实体行原样转换
        Page<StudentMessageVO> voPage =
                new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream()
                .flatMap(entity -> toStudentVos(entity, statusByRunId.get(entity.getRunId())).stream())
                .collect(Collectors.toList()));
        return voPage;
    }

    /**
     * 实体行 → 学生历史 VO 列表（消息实体化 2026-08-29，C 端历史消费面；M4 终态随行下发）。
     *
     * <p>assistant 实体行经 {@link AssistantEntitySplitter} 拆行还原事件序行（thinking /
     * query_plan / TOOL_CALL / 正文），非实体行（增量行/存量行）原样单条——前端
     * history-adapter 消费的 VO 行类型与实体化前完全一致（零改动）；检索来源仅正文行
     * （messageType=null）携带（实体行取自实体行 sources_json、非实体行取自身行值——
     * 与实体化前「正文行落真实来源、其余行 []」口径一致），附件仅用户行携带。
     * M4：run 终态/错误信息仅随终态 run 的非 USER 行下发（USER 行即使 runId 命中终态
     * run 也恒 null——spec「仅终态行」语义，以 entity.getRole() 判定）。
     *
     * @param entity    查询投影行（含 sources_json/attachments_json/thinking_stage）
     * @param runStatus 该行所属 run 的终态状态（statusByRunId 查得；非终态 run 行/USER 行为 null）
     * @return 学生历史 VO 列表（拆行 0 条时为空列表）
     */
    private List<StudentMessageVO> toStudentVos(ChatMessage entity, ChatRunStatusVO runStatus) {
        List<ChatMessageVO> vos = AssistantEntitySplitter.splitEntity(chatSessionConverter.toMessageVO(entity));
        // sources/attachments 解析复用 StudentConverter 既有 @Named 解析（非法 JSON 兜底空列表）
        List<RetrievalSource> sources = studentConverter.parseSources(entity.getSourcesJson());
        List<AttachmentRecord> attachments = studentConverter.parseAttachments(entity.getAttachmentsJson());
        // M4：run 终态仅非 USER 行下发（USER 行恒 null）；错误信息仅 ERROR 行下发（徽标 tooltip）
        boolean nonUserRow = !"USER".equals(entity.getRole());
        String voRunStatus = nonUserRow && runStatus != null ? runStatus.status() : null;
        String voErrorMessage =
                nonUserRow && runStatus != null && "ERROR".equals(runStatus.status()) ? runStatus.errorMessage() : null;
        return vos.stream()
                .map(vo -> new StudentMessageVO(
                        vo.id(),
                        vo.role(),
                        vo.content(),
                        vo.messageType(),
                        vo.thinkingStage(),
                        vo.intentType(),
                        vo.runId(),
                        vo.seq(),
                        vo.createdAt(),
                        vo.messageType() == null ? sources : List.of(),
                        attachments,
                        voRunStatus,
                        voErrorMessage))
                .toList();
    }

    /**
     * 统计 run 的消息数量
     *
     * @param runId Run ID
     * @return 消息数量
     */
    public long countByRunId(Long runId) {
        LambdaQueryWrapper<ChatMessage> wrapper =
                Wrappers.<ChatMessage>lambdaQuery().eq(ChatMessage::getRunId, runId);
        return messageMapper.selectCount(wrapper);
    }

    /**
     * 软删会话内指定 run 起的全部消息行（M5 replay，语义详见接口 javadoc）
     *
     * <p>本 service 主表走内置链式（this.lambdaUpdate().remove()）批量逻辑删除
     * （@TableLogic 置 deleted=1 保留审计）；EDIT 与 REGENERATE 统一为
     * 「runId >= fromRunId」范围（REGENERATE 时目标即最后一个 run，范围等价单 run）。
     *
     * @param sessionId 会话 ID（归属校验已通过）
     * @param fromRunId 起始 run ID（含，= replay 目标 run）
     */
    @Override
    @Transactional
    public void softDeleteFromRun(Long sessionId, Long fromRunId) {
        // 批量逻辑删除：session 内 runId >= fromRunId 的全部行（软删保留审计，历史查询经
        // @TableLogic 自动过滤，新 run 行不受影响）
        this.lambdaUpdate()
                .eq(ChatMessage::getSessionId, sessionId)
                .ge(ChatMessage::getRunId, fromRunId)
                .remove();
        log.info("replay 软删消息行: sessionId={}, fromRunId={}", sessionId, fromRunId);
    }
}
