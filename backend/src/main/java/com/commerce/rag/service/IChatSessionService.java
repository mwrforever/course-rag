package com.commerce.rag.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.commerce.rag.entity.ChatSession;
import com.commerce.rag.vo.ChatSessionVO;
import com.commerce.rag.vo.SessionVO;

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
     * @return 已持久化的会话视图对象（含雪花 ID）
     */
    SessionVO createSession(Long userId, String title);

    /**
     * 分页查询用户的活跃会话（按 last_message_at 降序）
     *
     * @param userId 用户 ID
     * @param page   页码（1-based）
     * @return 分页结果
     */
    IPage<ChatSession> findActiveSessions(Long userId, int page);

    /**
     * 更新会话标题（并刷新 updated_at）
     *
     * @param sessionId 会话 ID
     * @param title     新标题
     */
    void updateTitle(Long sessionId, String title);

    /**
     * 重命名会话并返回最新视图（C 端 PATCH 端点使用）
     *
     * @param sessionId 会话 ID
     * @param title     新标题（调用方已过 @NotBlank @Size(max=300) 校验）
     * @return 重命名后的会话视图对象
     */
    SessionVO renameSession(Long sessionId, String title);

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
     * @return 会话摘要视图对象，不存在返回 null
     */
    ChatSessionVO findById(Long sessionId);

    /**
     * 分页查询全部会话（管理端，不限用户和状态）
     *
     * @param page 页码（1-based）
     * @param size 每页条数
     * @return 分页结果（records 为会话摘要视图对象）
     */
    IPage<ChatSessionVO> findAllSessions(int page, int size);

    /**
     * 分页查询用户会话（不限状态），支持标题模糊搜索
     *
     * @param userId  用户 ID
     * @param page    页码（1-based）
     * @param size    每页条数
     * @param keyword 标题搜索关键词（可选，空/null = 全量列表）
     * @return 分页结果（records 为会话视图对象，不含 userId 等内部字段）
     */
    IPage<SessionVO> findSessionsByUser(Long userId, int page, int size, String keyword);

    /**
     * 删除会话（级联软删消息 + Run）
     *
     * @param sessionId 会话 ID
     * @param operatorId 操作者 ID（用于审计日志）
     */
    void deleteSession(Long sessionId, Long operatorId);
}
