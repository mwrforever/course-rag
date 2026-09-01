# TASK.md — 待办任务清单

> 记录「暂缓落地、需前置条件」的任务。已完成事项见 git 历史，不在此列。
> 2026-08-26 清理：多实例部署（§1）、前端 E2E（§2）、JaCoCo 补测（§3）、sparse 整改（§4）、
> 宪法调研回填（§5）、双前端 UI 重构与 vue-query 批 1-4（§6）均已实施完成，从本清单剔除；
> 批 4 遗留「上传进度条 E2E 缺口」转登记于 `docs/progress/2026-08-26-vue-query批4完成.md`；
> 「应用侧 BM25 向量」候选因官方方案（jieba + BM25 Function）生效而废止。

---

## 0. 注册发码配额的反代部署前提（PR#17 复审备注登记）

- [ ] **引入可信反向代理/ingress 时**：发码 IP 配额当前绑定 `request.getRemoteAddr()`（不可伪造，
      PR#17 审查 F1 定案）。上反代后 remoteAddr 会收敛为代理出口 IP——全站共享同一配额桶
      （失效方向为可用性限速而非安全旁路，方向保守可接受）。届时需配套：开启 Tomcat
      forward-headers 策略并强制代理覆写 XFF，或扩展 RegisterProperties 增加 trusted-proxy 开关
      只采信链尾地址。登记日期 2026-08-27。

---

## 1. Milvus sparse 检索后续（2026-08-26 整改完成后的遗留项）

**背景**：sparse/BM25 检索已按维护者官方用法整改实施完成（jieba 中文分词 + schema 版本标记 +
`retrieval.sparse-enabled=true`，PR#12 合 dev；dev 实证中文检索无崩溃）。此处仅剩定时与可选事项：

- [ ] **季度复查 milvus-sdk-java#1402**（下次 2026-11 前后）：整改已不依赖 SDK 修复；复查确认 issue
      状态，若官方修复发布可评估简化方案。实施细节见 git 历史（PR#12）+ `docs/progress/2026-08-26-sparse恢复整改-拍板与实施要点.md`
- [ ] **pymilvus 对照复核**（可选）：SDK 实证已充分（runAnalyzer 中文分词与官方示例一致 + 中文混合检索
      命中 + 服务端无崩溃），对照验证留作后续可选复核
- 回退预案（登记不撤）：`retrieval.sparse-enabled=false` 一行还原 dense-only 降级；jieba 已实证平台
      x86_64，arm64 部署前需复核 gojieba（cgo）可用性

---

## 2. B 端路由淡入过渡重评（前置 = Vue/vue-router 依赖升级）

**背景**：vue@3.5.41 `<Transition>` 包 RouterView 插槽缺陷实证——导航后新视图永不挂载（真浏览器
时间轴采样，与 key 取值/out-in 无关），页面淡入过渡已移除。resolvePageKey 修复必须保留
（同实体子路由切换壳存活不重取数）。

**当前状态**：2026-08-26 核实 npm 最新 vue 仍 3.5.41（vue 3.6 未发布，vue-router 已 4.6.4 无需等待）→ 维持登记。

**接入清单**：
- [ ] vue 3.6 发布后升级 `frontend/` 依赖 → `corepack pnpm dev`（端口 5001）→ Playwright 真浏览器
      验证页面切换淡入播放且内容挂载（AdminLayout.vue 内容区注释标明恢复点）
- [ ] 回归 resolvePageKey 语义（子路由切换不重取数）+ 双端门禁全绿

---

## 3. C 端意图体系相关 UI 微调（无具体项，等用户提出）

**背景**：C 端意图体系（knowledge_question / chat / unknown）相关 UI 微调无明确需求。

**当前状态**：无行动；用户提出具体项后按常规流程立项。

---

## 4. 封面孤儿对象巡检（2026-08-29 课程购买任务遗留，契约 D.2.5）

**背景**：封面上传 `POST /api/v1/admin/courses/cover` 按宪法 A.5.7「uuid 先占资源再落库」实现——上传成功但
课程未保存/未引用的封面 MinIO 对象（`0/` 前缀图片）无自动清理路径（MinIO 删除非即时，不得依赖生命周期做
即时语义，应用层补偿为既定方向）。dev 环境验收遗留 2 个孤儿封面对象（预期内，不影响功能）。

- [ ] **封面孤儿对象巡检任务**（低频）：比对 MinIO `0/` 前缀对象与 `course_info.cover_image` 引用，
      无引用且超过保留期（建议 7 天，给「上传后暂存草稿」留窗口）的对象删除；可挂靠既有补偿巡检体系
      或做独立低频 job。引入时机：封面量级或 MinIO 存储成本可感知时启动，届时阈值配置化归 properties/。
      登记日期 2026-08-29。

---

## 5. 流式时间轴分支 deferred 登记（feature/2026-08-28-chat-streaming-timeline，2026-08-29 Task 15a 收口）

> 批一/批二 SDD 执行与审核中裁决「不处理/后置」的遗留，登记于此随后续批次处理；完成即删本节对应行。
> 2026-08-29 消息实体化批收口：M-2 配置迁移（T-4）、Task 2B deferred ①③④（T-2）、findBySessionId 投影（T-1 实体化下自然消解）三项已完成删除；A.2.2 @Value 混用项未在本次范围，保留待办。

> 2026-08-29 消息实体化批收口后本节仅余 A.2.2 @Value 混用一项；该项已于 2026-09-01 由项目风险扫描修复
> BUG-12（PR#28，@Value 31 处→0 全量收敛 ConfigurationProperties）覆盖解决，待办删除、本节关闭。

---

## 6. 对话链路打通轮遗留（2026-08-31 三项已拍板实施，本节关闭）

> 2026-08-30「对话链路打通与流式修复」任务（见 CHANGELOG）验收与审核中的建议级发现。
> **2026-08-31 用户对三项全部拍板并实施**（PR 见 CHANGELOG 同日记录）：
> - 记忆提取超时 10s→1 分钟（A-1，用户两改定稿：先拍板 5 分钟当日改判 1 分钟）——MemoryProperties 默认值与 application.yml 同步 60000ms
> - c_rt_live 清理不对称收口（Finding 1）——auth-context 挂载无 RT 分支兜底清除残留提示 cookie
> - c_rt_live 生产 Secure 属性（Finding 2）——写入串 https 环境条件追加
> 原三项待办行已删除；S1 spec 文件缺失（F4）用户裁定为中间产物不回补（docs/ 不入库所致）。

---

## 7. 项目风险扫描修复·待用户拍板事项（2026-09-01 PR#28 终验汇总，只登记不实施）

> 全部为「需用户拍板的设计/部署/产品决策」——修复队列已全部落地（见 CHANGELOG 2026-09-01 记录），以下事项
> 逐项现状与建议详见 `docs/progress/2026-08-31-项目风险扫描修复.md`「待用户决策」节与终验报告。拍板后按常规
> 流程立项，完成即删本节对应行。

- [ ] **N3-② ERROR run 刷新后回答消失**：assistant 半截过滤为 M3 拍板契约（findCompletedRunIds 仅保留 COMPLETED run）——是否保留失败现场属产品决策
- [ ] **N3-③ 流式链路零重试**：主对话流式调用（OpenAiChatModel.internalStream）无 RetryTemplate，单次连接 reset 即整 run 失败——是否加受控重试/备选模型切换属设计变更（retry 配置化本体已修）
- [ ] **N3-④ 主 agent 流无 per-chunk 超时**：仅 blockLast 5min 兜底、失败静默窗口 ~15s——「静默多久判死」阈值与失败语义需拍板
- [ ] **PERF-21 公开课程端点缓存**：现「不做缓存」为显式注释决策；分层预审已过（Spring Cache + 60s TTL + afterCommit），拍板即可实施
- [ ] **PERF-26 课程中心切 tab/排序交错入场动效**：2026-08-31 改版拍板意图——需 Profiler 证实卡顿 + 用户确认视觉等价后立项
- [ ] **PERF-20 激进异步化**：保守方案已修；完全异步化引入检索可见性窗口，推翻 2026-08-15「同步语义」裁决须重新拍板
- [ ] **BUG-25 C 端 7 页面全 CSR 收缩**：全 CSR 为设计拍板形态、与宪法 C.1.3 张力——确认后逐页收缩 "use client"
- [ ] **证据不足 2 条**：①next.config rewrites 代理 localhost:8080 缺生产部署拓扑决策；②教师池 size:100 截断缺规模数据（破百需后端 keyword/前端翻页）
- [ ] **N3 结论告知**：慢响应+思考-only 根因=外部因素（DashScope 间歇断连 × 流式零重试），非代码 bug（处置建议并入 N3-③）

---
