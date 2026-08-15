package com.commerce.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.commerce.rag.entity.ChatMessage;
import com.commerce.rag.mapper.ChatMessageMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 消息服务 —— 批量持久化与查询 chat_message
 *
 * <p>设计文档 §3.5：run 结束后一次性批量 INSERT 消息，
 * 使用 JdbcTemplate.batchUpdate 保证性能（不在 MyBatis Mapper 里循环单条 insert）。
 *
 * <p>单条查询和列表查询走 MyBatis-Plus Lambda 链式 API。
 *
 * @author commerce-rag
 */
@Service
@RequiredArgsConstructor
public class ChatMessageService {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageService.class);

    /** 批量插入 SQL（deleted 固定 0 = 未删除，created_at 由数据库生成） */
    private static final String BATCH_INSERT_SQL =
            "INSERT INTO chat_message (id, session_id, role, content, intent_type, sources_json, "
                    + "token_count, run_id, seq, confidence, trace_id, message_type, deleted, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, now())";

    private final ChatMessageMapper messageMapper;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 批量插入消息（run 结束后一次性写入）
     *
     * <p>使用 JdbcTemplate.batchUpdate 高效批量插入。
     * 如果消息未设置 ID，自动通过 {@link IdWorker#get()} 分配雪花 ID。
     *
     * @param messages 消息列表
     */
    public void batchInsert(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (ChatMessage msg : messages) {
            if (msg.getId() == null) {
                msg.setId(IdWorker.getId());
            }
        }
        log.info("批量插入消息: count={}", messages.size());
        jdbcTemplate.batchUpdate(BATCH_INSERT_SQL, messages, messages.size(), (ps, msg) -> {
            ps.setLong(1, msg.getId());
            ps.setLong(2, msg.getSessionId());
            ps.setString(3, msg.getRole());
            ps.setString(4, msg.getContent());
            ps.setString(5, msg.getIntentType());
            ps.setString(6, msg.getSourcesJson() != null ? msg.getSourcesJson() : "[]");
            ps.setObject(7, msg.getTokenCount());
            ps.setLong(8, msg.getRunId());
            ps.setInt(9, msg.getSeq());
            ps.setObject(10, msg.getConfidence());
            ps.setString(11, msg.getTraceId());
            ps.setString(12, msg.getMessageType());
        });
    }

    /**
     * 按 run_id 查询消息（按 seq 升序），用于降级重组和前端历史回放
     *
     * @param runId Run ID
     * @return 消息列表（按 seq 升序）
     */
    public List<ChatMessage> findByRunId(Long runId) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getRunId, runId)
                .orderByAsc(ChatMessage::getSeq);
        return messageMapper.selectList(wrapper);
    }

    /**
     * 按 session_id 查询全部消息（按 created_at 升序），用于管理端会话详情
     *
     * @param sessionId 会话 ID
     * @return 消息列表
     */
    public List<ChatMessage> findBySessionId(Long sessionId) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByAsc(ChatMessage::getCreatedAt);
        return messageMapper.selectList(wrapper);
    }

    /**
     * 统计 run 的消息数量
     *
     * @param runId Run ID
     * @return 消息数量
     */
    public long countByRunId(Long runId) {
        LambdaQueryWrapper<ChatMessage> wrapper =
                new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getRunId, runId);
        return messageMapper.selectCount(wrapper);
    }
}
