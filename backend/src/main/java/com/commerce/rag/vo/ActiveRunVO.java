package com.commerce.rag.vo;

/**
 * 会话活跃 Run 视图对象（多会话并发续流锚点，2026-09-01 用户拍板）
 *
 * <p>C 端允许同一用户同时开启多会话问答：前端进入会话页时查询当前活跃 run，
 * 存在则据此发起 GET reconnect 全量回放续流（恢复进行中回答的实时视图）。
 *
 * @param runId 活跃 run ID（字符串，与 runId 路径参数风格一致）；无活跃 run 时为 null
 * @author commerce-rag
 */
public record ActiveRunVO(String runId) {}
