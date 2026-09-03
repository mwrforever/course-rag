package com.commerce.rag.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.commerce.rag.entity.ChatMessage;
import com.commerce.rag.vo.ChatMessageVO;
import com.commerce.rag.vo.StudentMessageVO;
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
     * 按 session_id 查询全部消息（按 created_at + seq 升序），用于管理端会话详情
     *
     * <p>M5 排序修复：复合排序消除同事务 saveBatch 批内 created_at 相同导致的
     * 排序不稳定（run 内唯一性由 V13 (run_id, seq) 唯一索引保证）。
     *
     * @param sessionId 会话 ID
     * @return 消息视图对象列表（剔除 sessionId/sourcesJson 等内部字段）
     */
    List<ChatMessageVO> findBySessionId(Long sessionId);

    /**
     * 学生端分页查询会话历史消息（R1 补口 A，升序返回最旧一页）
     *
     * <p>M3 半截过滤：两步查询——先经 IChatRunService 取会话内 COMPLETED runId 列表，
     * 再查消息表 {@code role='USER' or run_id in (completedRunIds)}，剔除取消/异常
     * run 的半截内容（用户提问痕迹保留）；空 runId 列表退化为仅查 USER 行。
     * 排序 created_at asc + seq asc 复合；投影含 sources_json/attachments_json
     * （服务端解析为对象数组）；size 上限钳制 500。
     *
     * @param sessionId 会话 ID（调用方须先完成归属校验）
     * @param page      页码（1-based，升序 page=1 即最旧一页，符合聊天 UI 渲染方向）
     * @param size      每页条数（超 500 钳制为 500）
     * @return 学生消息 VO 分页（entity 不出 service 边界）
     */
    IPage<StudentMessageVO> findStudentMessagesBySession(Long sessionId, int page, int size);

    /**
     * 统计 run 的消息数量
     *
     * @param runId Run ID
     * @return 消息数量
     */
    long countByRunId(Long runId);

    /**
     * 软删会话内指定 run 起的全部消息行（M5 replay，spec D2）
     *
     * <p>EDIT / REGENERATE 回滚的消息行软删入口：按 {@code session_id = ? AND
     * run_id >= ?} 批量逻辑删除（deleted=1 保留审计，@TableLogic 自动附加原
     * deleted=0 条件）；REGENERATE 传入 targetRunId 时因 D5 位置校验保证目标即
     * 最后一个 run，范围条件等价于仅目标 run 的行。checkpoint 历史不动（审计留痕）。
     *
     * <p>事务边界（A.4.12）：本方法自带 @Transactional 最小边界；与
     * IChatRunService.prepareReplayRun 的 run 行软删不在同一事务（两 service 互注
     * 成环禁止，B.2.2），中间态崩溃窗口由 replay 幂等收敛（软删幂等）。
     *
     * @param sessionId 会话 ID（归属校验已通过）
     * @param fromRunId 起始 run ID（含，= replay 目标 run）
     */
    void softDeleteFromRun(Long sessionId, Long fromRunId);
}
