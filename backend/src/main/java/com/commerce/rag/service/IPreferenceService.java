package com.commerce.rag.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.commerce.rag.entity.UserPreference;
import com.commerce.rag.record.PreferenceExtractionResult;
import java.util.List;

/**
 * 用户偏好服务 —— 决策执行/落库/注入读取（主表 UserPreference，spec §7）
 *
 * <p>全链路 user_id 硬隔离：所有写/读一律强制 user_id 过滤（spec §10-6），
 * 不信任外部传入过滤参数。
 *
 * @author commerce-rag
 */
public interface IPreferenceService extends IService<UserPreference> {

    /**
     * 执行一次偏好提取结果的落库（事务原子写，spec §7.1「PG 事务是唯一写入口」）
     *
     * <p>流程：DELETE 动作先软删 → 逐候选取 (user_id,key) 既有行 → 决策引擎 → 按动作执行
     * （CREATE/REINFORCE/OBSERVE_*、PROMOTE/UPDATE/IGNORE）。决策纯系统规则，无 LLM 参与。
     *
     * @param userId 所属用户（硬隔离过滤键，null 直接返回 0 不写）
     * @param result 提取结果（候选 + 删除意图，可为空）
     * @return 生效的动作数（IGNORE/DELETE 未命中不计）
     */
    int applyExtraction(Long userId, PreferenceExtractionResult result);

    /**
     * 该用户既有偏好文本（提取 prompt 开放型 key 同义收敛参考，spec §7.4-③）
     *
     * @param userId 所属用户
     * @return "标签:值" 每行一条，无偏好返回「无」
     */
    String findExistingValuesText(Long userId);

    /**
     * 查该用户全部 active 偏好行（注入块组装用，spec §7.8）
     *
     * @param userId 所属用户
     * @return active 行列表（含 key/value/writeScore/status，按 writeScore 降序）
     */
    List<UserPreference> findActiveForInjection(Long userId);
}
