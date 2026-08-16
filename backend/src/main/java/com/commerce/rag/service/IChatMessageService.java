package com.commerce.rag.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.commerce.rag.entity.ChatMessage;
import com.commerce.rag.vo.ChatMessageVO;
import java.util.List;

/**
 * 消息服务接口 —— 批量持久化与查询 chat_message（主表 ChatMessage）
 *
 * @author commerce-rag
 */
public interface IChatMessageService extends IService<ChatMessage> {

    /**
     * 批量插入消息（run 结束后一次性写入）
     *
     * <p>使用 MP saveBatch（JDBC 批处理，自动雪花 ID），须在事务内调用。
     *
     * @param messages 消息列表
     */
    void batchInsert(List<ChatMessage> messages);

    /**
     * 按 run_id 查询消息（按 seq 升序），用于降级重组和前端历史回放
     *
     * @param runId Run ID
     * @return 消息视图对象列表（剔除 sessionId/sourcesJson 等内部字段）
     */
    List<ChatMessageVO> findByRunId(Long runId);

    /**
     * 按 session_id 查询全部消息（按 created_at 升序），用于管理端会话详情
     *
     * @param sessionId 会话 ID
     * @return 消息视图对象列表（剔除 sessionId/sourcesJson 等内部字段）
     */
    List<ChatMessageVO> findBySessionId(Long sessionId);

    /**
     * 统计 run 的消息数量
     *
     * @param runId Run ID
     * @return 消息数量
     */
    long countByRunId(Long runId);
}
