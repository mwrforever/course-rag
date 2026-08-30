package com.commerce.rag.record;

/**
 * 检索来源条目（B3-5：SOURCES 事件与 chat_message.sourcesJson 的载荷单元）
 *
 * <p>RetrieveNode 检索命中非空时按精排结果组装来源列表，经 config.metadata()
 * {@code RetrieveNode.KEY_RETRIEVAL_SOURCES} 传递给 ChatRequestWorker：
 * <ul>
 *   <li>SSE SOURCES 事件 payload（前端「引用来源」卡片与召回片段抽屉渲染）</li>
 *   <li>chat_message.sourcesJson 持久化（assistant 正文行的引用来源）</li>
 * </ul>
 *
 * <p>payload 字段依据：契约文档（docs/contracts/2026-08-16-接口契约定稿.md）未细化
 * SOURCES 事件结构，按前端设计 docs/plans/2026-07-16-frontend-design.md §1.6.4
 * 「sources → 列表渲染，替换临时占位」取最小可用字段（标题/章节/分数 + chunkId 溯源）；
 * 2026-08-27 C 端改版补充 content（片段正文截断），供召回文档抽屉直接展示片段内容；
 * 2026-08-30 懒加载改版移除 content——检索内容可能较大，不再一次性下发前端，抽屉
 * 点击展开时前端按 chunkId 调 /chunks/{id}/context 回查 PG（存量 sources_json 携带
 * content 字段，前端容错缺失）。
 *
 * @param chunkId     分片唯一标识（B 端溯源；抽屉展开按 id 回查 PG）
 * @param docTitle    来源文档标题（PG document.title 回查填充，B3-3；缺失为空串）
 * @param headingPath 来源文档内章节路径（如 "Ch3 &gt; 3.2"，可空）
 * @param score       rerank 精排分数（0.0 ~ 1.0）
 */
public record RetrievalSource(String chunkId, String docTitle, String headingPath, double score) {}
