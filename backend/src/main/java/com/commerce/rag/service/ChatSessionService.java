package com.commerce.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.entity.ChatMessage;
import com.commerce.rag.entity.ChatRun;
import com.commerce.rag.entity.ChatSession;
import com.commerce.rag.mapper.ChatMessageMapper;
import com.commerce.rag.mapper.ChatRunMapper;
import com.commerce.rag.mapper.ChatSessionMapper;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 会话服务 —— 封装 chat_session 表的 CRUD 操作
 *
 * <p>使用 MyBatis-Plus LambdaQueryWrapper / LambdaUpdateWrapper 链式 API。
 * 所有查询自动过滤逻辑删除记录（@TableLogic）。
 *
 * @author commerce-rag
 */
@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionService.class);

    /** 会话列表默认每页条数 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final ChatRunMapper runMapper;

    /**
     * 创建新会话
     *
     * @param userId 用户 ID
     * @param title  会话标题
     * @return 已持久化的会话实体（含雪花 ID）
     */
    public ChatSession createSession(Long userId, String title) {
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setTitle(title);
        session.setStatus("ACTIVE");
        sessionMapper.insert(session);
        log.info("创建会话: sessionId={}, userId={}", session.getId(), userId);
        return session;
    }

    /**
     * 分页查询用户的活跃会话（按 last_message_at 降序）
     *
     * @param userId 用户 ID
     * @param page   页码（1-based）
     * @return 分页结果
     */
    public IPage<ChatSession> findActiveSessions(Long userId, int page) {
        log.info("查询活跃会话: userId={}, page={}", userId, page);
        Page<ChatSession> pageObj = new Page<>(page, DEFAULT_PAGE_SIZE);
        LambdaQueryWrapper<ChatSession> wrapper = Wrappers.<ChatSession>lambdaQuery()
                .eq(ChatSession::getUserId, userId)
                .eq(ChatSession::getStatus, "ACTIVE")
                .orderByDesc(ChatSession::getLastMessageAt);
        return sessionMapper.selectPage(pageObj, wrapper);
    }

    /**
     * 更新会话标题
     *
     * @param sessionId 会话 ID
     * @param title     新标题
     */
    public void updateTitle(Long sessionId, String title) {
        log.info("更新会话标题: sessionId={}, title={}", sessionId, title);
        LambdaUpdateWrapper<ChatSession> wrapper = Wrappers.<ChatSession>lambdaUpdate()
                .eq(ChatSession::getId, sessionId)
                .set(ChatSession::getTitle, title);
        sessionMapper.update(null, wrapper);
    }

    /**
     * 更新会话最后消息时间为当前时刻
     *
     * @param sessionId 会话 ID
     */
    public void updateLastMessageAt(Long sessionId) {
        LambdaUpdateWrapper<ChatSession> wrapper = Wrappers.<ChatSession>lambdaUpdate()
                .eq(ChatSession::getId, sessionId)
                .set(ChatSession::getLastMessageAt, LocalDateTime.now());
        sessionMapper.update(null, wrapper);
    }

    /**
     * 关闭会话（status → CLOSED）
     *
     * @param sessionId 会话 ID
     */
    public void closeSession(Long sessionId) {
        log.info("关闭会话: sessionId={}", sessionId);
        LambdaUpdateWrapper<ChatSession> wrapper = Wrappers.<ChatSession>lambdaUpdate()
                .eq(ChatSession::getId, sessionId)
                .set(ChatSession::getStatus, "CLOSED");
        sessionMapper.update(null, wrapper);
    }

    // ==================== 管理端方法 ====================

    /**
     * 根据 ID 查询会话
     *
     * @param sessionId 会话 ID
     * @return 会话实体，不存在返回 null
     */
    public ChatSession findById(Long sessionId) {
        return sessionMapper.selectById(sessionId);
    }

    /**
     * 分页查询全部会话（管理端，不限用户和状态）
     *
     * @param page 页码（1-based）
     * @param size 每页条数
     * @return 分页结果
     */
    public IPage<ChatSession> findAllSessions(int page, int size) {
        Page<ChatSession> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<ChatSession> wrapper =
                Wrappers.<ChatSession>lambdaQuery().orderByDesc(ChatSession::getLastMessageAt);
        return sessionMapper.selectPage(pageObj, wrapper);
    }

    /**
     * 分页查询用户会话（不限状态，管理端用）
     *
     * @param userId 用户 ID
     * @param page   页码（1-based）
     * @param size   每页条数
     * @return 分页结果
     */
    public IPage<ChatSession> findSessionsByUser(Long userId, int page, int size) {
        Page<ChatSession> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<ChatSession> wrapper = Wrappers.<ChatSession>lambdaQuery()
                .eq(ChatSession::getUserId, userId)
                .orderByDesc(ChatSession::getLastMessageAt);
        return sessionMapper.selectPage(pageObj, wrapper);
    }

    /**
     * 删除会话（级联软删消息 + Run）
     *
     * @param sessionId 会话 ID
     * @param operatorId 操作者 ID（用于审计日志）
     */
    public void deleteSession(Long sessionId, Long operatorId) {
        long ts = System.currentTimeMillis();
        // 级联软删 chat_message + chat_run（使用 MyBatis-Plus LambdaUpdateWrapper）
        messageMapper.update(
                null,
                Wrappers.<ChatMessage>lambdaUpdate()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .eq(ChatMessage::getDeleted, 0)
                        .set(ChatMessage::getDeleted, ts));
        runMapper.update(
                null,
                Wrappers.<ChatRun>lambdaUpdate()
                        .eq(ChatRun::getSessionId, sessionId)
                        .eq(ChatRun::getDeleted, 0)
                        .set(ChatRun::getDeleted, ts));
        // 软删会话本身
        LambdaUpdateWrapper<ChatSession> wrapper = Wrappers.<ChatSession>lambdaUpdate()
                .eq(ChatSession::getId, sessionId)
                .set(ChatSession::getDeleted, ts)
                .set(ChatSession::getUpdatedAt, LocalDateTime.now());
        sessionMapper.update(null, wrapper);
        log.info("级联软删会话: sessionId={}, operatorId={}", sessionId, operatorId);
    }
}
