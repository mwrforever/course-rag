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

## 4. 流式时间轴分支 deferred 登记（feature/2026-08-28-chat-streaming-timeline，2026-08-29 Task 15a 收口）

> 批一/批二 SDD 执行与审核中裁决「不处理/后置」的四项，登记于此随后续批次处理；完成即删本节对应行。

- [ ] **A.2.2 rag.query-understanding 前缀 @Value 混用收口**（批一 Task 3 minor）：该前缀下
      `model`/`max-queries` 两项仍经 `@Value` 散注而其余键走属性类强类型绑定，违反宪法
      A.2.2（禁止散落 `@Value` 逐条注入）。收口方式：并入既有属性类补两字段 + 启动期校验，
      改动局限 QueryUnderstanding 相关配置消费点。
- [ ] **M-2 配置命名空间挪用**（批一 Task 4 复审 T4-R 裁决维持不处理）：attachment caption
      相关配置项挂在非自有命名空间下（挪用了相邻前缀），涉及后端配置兼容（改前缀=运行时
      配置源失效），裁决登记待后续配置体系整理时一并迁移。
- [ ] **Task 2B deferred ①③④**（MemoryStreamBridge ring 实现，②已在 Task 6 收口加 warn 观测）：
      ① slot 改 `event.seqId()` 直取 + head 维护 max，彻底消除「取号-推送不变式」依赖
      （Task 7 实证交错窗口回放完整性受益）；③ 补 seqId 身份逐一断言测试（现按集合断言）；
      ④ `oldestSeq` 命名 nit（实义为 ring 内最小已写入序号）。
- [ ] **批一审核 Minor「findBySessionId 投影未同步」**：`ChatMessageMapper.findBySessionId`
      （B 端管理通道）投影 8 列未随 thinking_stage 加列（Task 6 只同步了 findByRunId /
      findStudentMessagesBySession），其注释「与 findByRunId 同款投影」现为近似表述；
      B 端无 stage 消费方故未动，严格同步时一并加列并修订注释。
