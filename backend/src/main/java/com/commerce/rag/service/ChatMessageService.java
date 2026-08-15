package com.commerce.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.commerce.rag.entity.ChatMessage;
import com.commerce.rag.mapper.ChatMessageMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 消息服务 —— 批量持久化与查询 chat_message
 *
 * <p>设计文档 §3.5：run 结束后一次性批量 INSERT 消息，
 * 使用 Mapper XML 多值 INSERT 保证性能（不在 MyBatis Mapper 里循环单条 insert）。
 *
 * <p>单条查询和列表查询走 MyBatis-Plus Lambda 链式 API。
 *
 * @author commerce-rag
 */
@Service
@RequiredArgsConstructor
public class ChatMessageService {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageService.class);

    private final ChatMessageMapper messageMapper;

    /**
     * 批量插入消息（run 结束后一次性写入）
     *
     * <p>使用 Mapper XML 多值 INSERT 高效批量插入。
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
            // sourcesJson 为 JSONB 列，空值兜底为 "[]"（与原 JdbcTemplate 参数绑定语义一致）
            if (msg.getSourcesJson() == null) {
                msg.setSourcesJson("[]");
            }
        }
        log.info("批量插入消息: count={}", messages.size());
        messageMapper.batchInsert(messages);
    }

    /**
     * 按 run_id 查询消息（按 seq 升序），用于降级重组和前端历史回放
     *
     * @param runId Run ID
     * @return 消息列表（按 seq 升序）
     */
    public List<ChatMessage> findByRunId(Long runId) {
        LambdaQueryWrapper<ChatMessage> wrapper = Wrappers.<ChatMessage>lambdaQuery()
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
        LambdaQueryWrapper<ChatMessage> wrapper = Wrappers.<ChatMessage>lambdaQuery()
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
                Wrappers.<ChatMessage>lambdaQuery().eq(ChatMessage::getRunId, runId);
        return messageMapper.selectCount(wrapper);
    }
}
