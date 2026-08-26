package com.commerce.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.commerce.rag.convert.ChatSessionConverter;
import com.commerce.rag.convert.StudentConverter;
import com.commerce.rag.entity.ChatMessage;
import com.commerce.rag.entity.ChatRun;
import com.commerce.rag.entity.ChatSession;
import com.commerce.rag.mapper.ChatMessageMapper;
import com.commerce.rag.mapper.ChatRunMapper;
import com.commerce.rag.mapper.ChatSessionMapper;
import com.commerce.rag.service.IChatSessionService;
import com.commerce.rag.vo.ChatSessionVO;
import com.commerce.rag.vo.SessionVO;
import java.time.LocalDateTime;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
public class ChatSessionServiceImpl extends ServiceImpl<ChatSessionMapper, ChatSession> implements IChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(IChatSessionService.class);

    /** 会话列表默认每页条数 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final ChatRunMapper runMapper;
    /** 会话转换器 —— 管理端摘要视图对象（ChatSessionVO） */
    private final ChatSessionConverter chatSessionConverter;
    /** 学生端转换器 —— C 端会话视图对象（SessionVO），转换器跨层共用合法 */
    private final StudentConverter studentConverter;

    /**
     * 创建新会话
     *
     * @param userId 用户 ID
     * @param title  会话标题
     * @return 已持久化的会话视图对象（含雪花 ID）
     */
    public SessionVO createSession(Long userId, String title) {
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setTitle(title);
        session.setStatus("ACTIVE");
        sessionMapper.insert(session);
        log.info("创建会话: sessionId={}, userId={}", session.getId(), userId);
        return studentConverter.toSessionVO(session);
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
     * 更新会话标题（同时刷新 updated_at）
     *
     * @param sessionId 会话 ID
     * @param title     新标题
     */
    public void updateTitle(Long sessionId, String title) {
        log.info("更新会话标题: sessionId={}, title={}", sessionId, title);
        // 本 service 主表操作：内置链式更新（A.4.3），同步刷新 updated_at 供审计留痕
        this.lambdaUpdate()
                .eq(ChatSession::getId, sessionId)
                .set(ChatSession::getTitle, title)
                .set(ChatSession::getUpdatedAt, LocalDateTime.now())
                .update();
    }

    /**
     * 重命名会话并返回最新视图（C 端 PATCH 端点使用）
     *
     * <p>存在性/归属校验在 controller 层完成（与删除端点先例一致），
     * 本方法只执行更新与回读，保证返回数据为持久化后状态。
     *
     * @param sessionId 会话 ID
     * @param title     新标题（调用方已过 @NotBlank @Size(max=300) 校验）
     * @return 重命名后的会话视图对象
     */
    public SessionVO renameSession(Long sessionId, String title) {
        updateTitle(sessionId, title);
        // 更新后重新查询回读（转换器与列表同源，保证契约一致）
        ChatSession session = sessionMapper.selectById(sessionId);
        return studentConverter.toSessionVO(session);
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
     * @return 会话摘要视图对象，不存在返回 null
     */
    public ChatSessionVO findById(Long sessionId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        return session == null ? null : chatSessionConverter.toSummaryVO(session);
    }

    /**
     * 分页查询全部会话（管理端，不限用户和状态）
     *
     * @param page 页码（1-based）
     * @param size 每页条数
     * @return 分页结果（records 为会话摘要视图对象）
     */
    public IPage<ChatSessionVO> findAllSessions(int page, int size) {
        Page<ChatSession> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<ChatSession> wrapper =
                Wrappers.<ChatSession>lambdaQuery().orderByDesc(ChatSession::getLastMessageAt);
        IPage<ChatSession> entityPage = sessionMapper.selectPage(pageObj, wrapper);
        // 实体分页 → VO 分页：records 逐条转换，total/current/size 分页语义保持
        Page<ChatSessionVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream()
                .map(chatSessionConverter::toSummaryVO)
                .collect(Collectors.toList()));
        return voPage;
    }

    /**
     * 分页查询用户会话（不限状态），支持标题模糊搜索
     *
     * @param userId  用户 ID
     * @param page    页码（1-based）
     * @param size    每页条数
     * @param keyword 标题搜索关键词（可选，空/null = 全量列表）
     * @return 分页结果（records 为会话视图对象，不含 userId 等内部字段）
     */
    public IPage<SessionVO> findSessionsByUser(Long userId, int page, int size, String keyword) {
        Page<ChatSession> pageObj = new Page<>(page, size);
        // 本 service 主表操作：内置链式查询；PDF 语义下 like 仅当关键词有效时追加（MP 参数化无注入风险）
        IPage<ChatSession> entityPage = this.lambdaQuery()
                .eq(ChatSession::getUserId, userId)
                .like(StringUtils.hasText(keyword), ChatSession::getTitle, keyword)
                .orderByDesc(ChatSession::getLastMessageAt)
                .page(pageObj);
        // 实体分页 → VO 分页：records 逐条转换，total/current/size 分页语义保持
        Page<SessionVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream()
                .map(studentConverter::toSessionVO)
                .collect(Collectors.toList()));
        return voPage;
    }

    /**
     * 删除会话（级联软删消息 + Run）
     *
     * <p>B2-5 事务说明：chat_message → chat_run → chat_session 三条软删 UPDATE 在同一事务内原子执行，
     * 中途任一步失败（连接池耗尽/瞬时故障）整体回滚，避免出现"消息已删而会话仍存活"的中间态。
     * 方法内无外部资源调用（纯 PG 写），事务边界即方法边界。
     *
     * @param sessionId 会话 ID
     * @param operatorId 操作者 ID（用于审计日志）
     */
    @Transactional
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
