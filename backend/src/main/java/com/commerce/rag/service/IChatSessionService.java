package com.commerce.rag.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.commerce.rag.entity.ChatSession;

/**
 * 会话服务接口 —— 封装 chat_session 表的 CRUD 操作（主表 ChatSession）
 *
 * @author commerce-rag
 */
public interface IChatSessionService extends IService<ChatSession> {

    /**
     * 创建新会话
     *
     * @param userId 用户 ID
     * @param title  会话标题
     * @return 已持久化的会话实体（含雪花 ID）
     */
    ChatSession createSession(Long userId, String title);

    /**
     * 分页查询用户的活跃会话（按 last_message_at 降序）
     *
     * @param userId 用户 ID
     * @param page   页码（1-based）
     * @return 分页结果
     */
    IPage<ChatSession> findActiveSessions(Long userId, int page);

    /**
     * 更新会话标题
     *
     * @param sessionId 会话 ID
     * @param title     新标题
     */
    void updateTitle(Long sessionId, String title);

    /**
     * 更新会话最后消息时间为当前时刻
     *
     * @param sessionId 会话 ID
     */
    void updateLastMessageAt(Long sessionId);

    /**
     * 关闭会话（status → CLOSED）
     *
     * @param sessionId 会话 ID
     */
    void closeSession(Long sessionId);

    /**
     * 根据 ID 查询会话
     *
     * @param sessionId 会话 ID
     * @return 会话实体，不存在返回 null
     */
    ChatSession findById(Long sessionId);

    /**
     * 分页查询全部会话（管理端，不限用户和状态）
     *
     * @param page 页码（1-based）
     * @param size 每页条数
     * @return 分页结果
     */
    IPage<ChatSession> findAllSessions(int page, int size);

    /**
     * 分页查询用户会话（不限状态，管理端用）
     *
     * @param userId 用户 ID
     * @param page   页码（1-based）
     * @param size   每页条数
     * @return 分页结果
     */
    IPage<ChatSession> findSessionsByUser(Long userId, int page, int size);

    /**
     * 删除会话（级联软删消息 + Run）
     *
     * @param sessionId 会话 ID
     * @param operatorId 操作者 ID（用于审计日志）
     */
    void deleteSession(Long sessionId, Long operatorId);
}
