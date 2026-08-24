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
import com.commerce.rag.service.IChatMessageService;
import com.commerce.rag.service.IChatRunService;
import com.commerce.rag.vo.ChatMessageVO;
import com.commerce.rag.vo.StudentMessageVO;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    /** Run 服务 —— 两步查询第一步取 COMPLETED runId（宪法：跨 service 走对方接口，禁直操作他人 mapper） */
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
     * 按 run_id 查询消息（按 seq 升序），用于降级重组和前端历史回放
     *
     * <p>L-2：按需取列——仅投影 ChatMessageVO 所需 8 列（sourcesJson/tokenCount/
     * confidence/traceId/sessionId 等大字段或内部字段丢弃）。
     *
     * @param runId Run ID
     * @return 消息视图对象列表（按 seq 升序，剔除 sessionId/sourcesJson 等内部字段）
     */
    public List<ChatMessageVO> findByRunId(Long runId) {
        LambdaQueryWrapper<ChatMessage> wrapper = Wrappers.<ChatMessage>lambdaQuery()
                .select(
                        ChatMessage::getId,
                        ChatMessage::getRole,
                        ChatMessage::getContent,
                        ChatMessage::getMessageType,
                        ChatMessage::getIntentType,
                        ChatMessage::getRunId,
                        ChatMessage::getSeq,
                        ChatMessage::getCreatedAt)
                .eq(ChatMessage::getRunId, runId)
                .orderByAsc(ChatMessage::getSeq);
        // 实体列表 → VO 列表：逐条转换，sessionId/sourcesJson 等内部字段不随 VO 出边界
        return messageMapper.selectList(wrapper).stream()
                .map(chatSessionConverter::toMessageVO)
                .collect(Collectors.toList());
    }

    /**
     * 按 session_id 查询全部消息（按 created_at + seq 升序），用于管理端会话详情
     *
     * <p>L-2：按需取列——与 findByRunId 同款投影（sourcesJson/tokenCount/confidence/traceId 丢弃）。
     * M5 排序修复：created_at 相同（同事务 saveBatch 批内）时按 seq 复合排序，消除排序不稳定。
     *
     * @param sessionId 会话 ID
     * @return 消息视图对象列表（剔除 sessionId/sourcesJson 等内部字段）
     */
    public List<ChatMessageVO> findBySessionId(Long sessionId) {
        LambdaQueryWrapper<ChatMessage> wrapper = Wrappers.<ChatMessage>lambdaQuery()
                .select(
                        ChatMessage::getId,
                        ChatMessage::getRole,
                        ChatMessage::getContent,
                        ChatMessage::getMessageType,
                        ChatMessage::getIntentType,
                        ChatMessage::getRunId,
                        ChatMessage::getSeq,
                        ChatMessage::getCreatedAt)
                .eq(ChatMessage::getSessionId, sessionId)
                // M5：复合排序（created_at 相同时按 run 内 seq 定序，批内插入顺序稳定）
                .orderByAsc(ChatMessage::getCreatedAt)
                .orderByAsc(ChatMessage::getSeq);
        // 实体列表 → VO 列表：逐条转换，sessionId/sourcesJson 等内部字段不随 VO 出边界
        return messageMapper.selectList(wrapper).stream()
                .map(chatSessionConverter::toMessageVO)
                .collect(Collectors.toList());
    }

    /**
     * 学生端分页查询会话历史消息（R1 补口 A）
     *
     * <p>执行流程：
     * <ol>
     *   <li>M3 半截过滤第一步——经 IChatRunService 取会话内 COMPLETED runId 列表
     *       （取消/异常 run 的 assistant 半截内容须剔除，与实时「已停止生成」语义一致）</li>
     *   <li>size 上限钳制 500（防御性，宪法查询必带分页）</li>
     *   <li>M3 第二步——消息表过滤 {@code session_id=? and (role='USER' or run_id in (...))}，
     *       空 runId 列表退化为仅查 USER 行（避免生成 IN () 非法 SQL）</li>
     *   <li>排序 created_at asc + seq asc 复合，升序 page=1 即最旧一页（聊天 UI 渲染方向）</li>
     *   <li>entity 分页 → 学生 VO 分页（sources/attachments JSON 由转换器解析，entity 不出边界）</li>
     * </ol>
     *
     * @param sessionId 会话 ID（调用方须先完成归属校验）
     * @param page      页码（1-based）
     * @param size      每页条数（超 500 钳制为 500）
     * @return 学生消息 VO 分页（records 含解析后的 sources/attachments 数组）
     */
    public IPage<StudentMessageVO> findStudentMessagesBySession(Long sessionId, int page, int size) {
        // M3 第一步：会话内 COMPLETED run 列表（经对方 service 接口，跨模块禁直操作 mapper）
        List<Long> completedRunIds = chatRunService.findCompletedRunIds(sessionId);
        // size 上限钳制（一轮工具调用产出 5+ 行，防止无界拉取）
        Page<ChatMessage> pageObj = new Page<>(page, Math.min(size, MAX_PAGE_SIZE));
        LambdaQueryWrapper<ChatMessage> wrapper = Wrappers.<ChatMessage>lambdaQuery()
                // 按需取列：投影补 sources_json/attachments_json（服务端解析 JSON 用），丢弃内部字段
                .select(
                        ChatMessage::getId,
                        ChatMessage::getRole,
                        ChatMessage::getContent,
                        ChatMessage::getMessageType,
                        ChatMessage::getIntentType,
                        ChatMessage::getRunId,
                        ChatMessage::getSeq,
                        ChatMessage::getCreatedAt,
                        ChatMessage::getSourcesJson,
                        ChatMessage::getAttachmentsJson)
                .eq(ChatMessage::getSessionId, sessionId);
        if (completedRunIds.isEmpty()) {
            // 会话内无已完成 run：退化为仅保留 USER 行（用户提问痕迹完整，半截回答全剔除）
            wrapper.eq(ChatMessage::getRole, "USER");
        } else {
            // M3 核心：USER 行 + COMPLETED run 的非 USER 行（OR 嵌套须包一层，避免与软删条件错误展开）
            wrapper.and(w -> w.eq(ChatMessage::getRole, "USER").or().in(ChatMessage::getRunId, completedRunIds));
        }
        // 复合排序：批内 created_at 相同时按 seq 定序（M5 同根因）
        wrapper.orderByAsc(ChatMessage::getCreatedAt).orderByAsc(ChatMessage::getSeq);
        IPage<ChatMessage> entityPage = messageMapper.selectPage(pageObj, wrapper);
        log.info(
                "查询学生历史消息: sessionId={}, page={}, rows={}, completedRuns={}",
                sessionId,
                page,
                entityPage.getRecords().size(),
                completedRunIds.size());
        // entity 分页 → 学生 VO 分页：分页元数据保持，records 经转换器解析 sources/attachments
        Page<StudentMessageVO> voPage =
                new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream()
                .map(studentConverter::toStudentMessageVO)
                .collect(Collectors.toList()));
        return voPage;
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
}
