package com.commerce.rag.enums;

/**
 * 经历记忆决策动作（spec §8.6 状态机语义，纯系统执行）
 *
 * @author commerce-rag
 */
public enum EpisodicActionType {
    /** 新事实：写 active 新行（version=1） */
    CREATE,
    /** 修正事实：旧行 validity=superseded + 新行 active version+1 */
    UPDATE,
    /** 同主题演进：旧行 validity=merged + 新行 active（content=合并陈述）version+1 */
    MERGE,
    /** 用户明确否定：目标行 validity=invalidated（无新行） */
    INVALIDATE,
    /** 忽略（分数不足/重复/未命中目标），不产生任何行 */
    IGNORE
}
