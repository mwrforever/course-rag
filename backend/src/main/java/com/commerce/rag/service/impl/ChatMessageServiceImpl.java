package com.commerce.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.commerce.rag.convert.ChatSessionConverter;
import com.commerce.rag.entity.ChatMessage;
import com.commerce.rag.mapper.ChatMessageMapper;
import com.commerce.rag.service.IChatMessageService;
import com.commerce.rag.vo.ChatMessageVO;
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

    private final ChatMessageMapper messageMapper;
    /** 会话转换器 —— 消息视图对象转换（toMessageVO） */
    private final ChatSessionConverter chatSessionConverter;

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
     * @return 消息视图对象列表（剔除 sessionId/sourcesJson 等内部字段）
     */
    public List<ChatMessageVO> findBySessionId(Long sessionId) {
        LambdaQueryWrapper<ChatMessage> wrapper = Wrappers.<ChatMessage>lambdaQuery()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByAsc(ChatMessage::getCreatedAt);
        // 实体列表 → VO 列表：逐条转换，sessionId/sourcesJson 等内部字段不随 VO 出边界
        return messageMapper.selectList(wrapper).stream()
                .map(chatSessionConverter::toMessageVO)
                .collect(Collectors.toList());
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
