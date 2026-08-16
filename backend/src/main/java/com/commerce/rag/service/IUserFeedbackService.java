package com.commerce.rag.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.commerce.rag.entity.UserFeedback;
import com.commerce.rag.vo.UserFeedbackVO;
import java.util.List;
import java.util.Map;

/**
 * 用户反馈服务接口 —— 反馈提交/分页/统计/删除（主表 UserFeedback）
 *
 * @author commerce-rag
 */
public interface IUserFeedbackService extends IService<UserFeedback> {

    /**
     * 提交反馈（按 user_id + message_id 幂等）
     *
     * @return 反馈实体（已存在时更新原记录）
     */
    UserFeedback create(Long userId, Long sessionId, Long messageId, Boolean isLiked, String intentType);

    /**
     * 分页查询反馈（管理端）
     */
    IPage<UserFeedbackVO> findPage(int page, int size, String intentType);

    /**
     * 反馈统计（按意图聚合，管理端）
     */
    List<Map<String, Object>> findStats();

    /**
     * 删除反馈（软删）
     */
    void delete(Long id, Long operatorId);
}
