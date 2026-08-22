package com.commerce.rag.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.commerce.rag.entity.UserEpisodicMemory;
import com.commerce.rag.record.EpisodicExtractionResult;
import com.commerce.rag.record.EpisodicMemoryRef;
import java.util.List;

/**
 * 用户经历记忆服务 —— 决策执行/落库/召回（主表 UserEpisodicMemory，spec §8）
 *
 * <p>全链路 user_id 硬隔离：所有写/读/召回/索引一律强制 user_id 过滤（spec §10-6），
 * 不信任外部传入过滤参数。
 *
 * @author commerce-rag
 */
public interface IEpisodicMemoryService extends IService<UserEpisodicMemory> {

    /**
     * 执行一次经历记忆提取结果的落库（spec §8.1 PG 事务原子写是唯一写入口）
     *
     * <p>逐记忆决策（is_memory=false / 分数不足 / 重复 / 未命中目标由决策引擎产出 IGNORE）→
     * 按动作执行状态机（CREATE 新行 / UPDATE+MERGE 旧行 superseded|merged + 新行 version+1 /
     * INVALIDATE 目标行）；DB 提交后 best-effort 同步 Milvus memory_chunks 召回索引（失败仅记
     * 日志不回滚，spec §8.5 PG 为事实源）。
     *
     * @param userId          所属用户（硬隔离过滤键，null 直接返回 0 不写）
     * @param sourceSessionId 来源会话（提取触发所在 run 的会话快照，可为 null）
     * @param result          提取结果（记忆条目列表，可为空）
     * @return 生效的动作数（IGNORE 不计）
     */
    int applyExtraction(Long userId, Long sourceSessionId, EpisodicExtractionResult result);

    /**
     * 该用户已有经历记忆文本（提取 prompt merge_target 原文引用参考，spec §8.4）
     *
     * @param userId 所属用户
     * @return active 记忆「标签:内容」每行一条，无记忆返回「无」
     */
    String findActiveMemoriesText(Long userId);

    /**
     * 经历记忆召回（spec §8.7：Milvus(user_id 过滤 + recall_history 动态 validity)
     * → memory_id → PG 主键批量取数 → 分数过滤 → topK）
     *
     * <p>查询向量由上游 {@code RetrieveNode} 预嵌入传入（方案 3-1-a：首条重写查询的 embedding
     * 与知识检索共用一次远程调用，不重复 embed）；向量为空直接降级空召回。
     *
     * @param userId        所属用户（硬隔离过滤键）
     * @param queryVector   召回查询向量（RetrieveNode 预嵌入，null/空 → 直接返回空列表）
     * @param recallHistory recall_history=false → validity=="active"；true → 不带 validity 条件（全量召回）
     * @param topK          返回条数上限
     * @return 按召回分降序的引用列表（无命中/失败返回空列表）
     */
    List<EpisodicMemoryRef> recall(Long userId, float[] queryVector, boolean recallHistory, int topK);
}
