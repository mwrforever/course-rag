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

- [ ] **A.2.2 rag.query-understanding 前缀 @Value 混用收口**（批一 Task 3 minor）：该前缀下
      `model`/`max-queries` 两项仍经 `@Value` 散注而其余键走属性类强类型绑定，违反宪法
      A.2.2（禁止散落 `@Value` 逐条注入）。收口方式：并入既有属性类补两字段 + 启动期校验，
      改动局限 QueryUnderstanding 相关配置消费点。

---

## 6. 对话链路打通轮遗留（2026-08-30 登记）

> 2026-08-30「对话链路打通与流式修复」任务（PR 见 CHANGELOG 同日记录）验收与审核中的建议级发现，
> 不阻塞合入、待用户决策或后续批次处理。

- [ ] **记忆提取超时阈值与模型实测延迟失配**（N3 验收 A-1）：S1 设计定稿「提取超时 10s 失败丢弃」，
      晚间提取位点（qwen3.7-max-2026-06-08）实测延迟 14~22s，验收窗 8 次目标提取 5 次超时丢弃（3 次成功均 <8s）；
      写入/召回/注入链路本身已全链路实证无缺陷。**需用户拍板**：将
      `memory.extraction.timeout-ms` 提至 ≥30000，或为提取位点换低延迟模型。**注意（N3 审核 F4）**：
      设计权威源 `docs/superpowers/specs/2026-08-12-multimodal-rag-design.md` 文件实际缺失（docs/ 不入库，
      specs/ 仅存 2026-08-24 一份），timeout=10s 的设计出处实证于 `.superpowers/sdd/2026-08-19-s1-plan4/5`
      计划与 application.yml 注释；修订前需先回补/确认权威源（见终验报告升级项）。登记日期 2026-08-30。
- [ ] **c_rt_live 提示 cookie 清理不对称收口**（N2 审核 Finding 1，cosmetic 级）：「localStorage 无 RT
      但 c_rt_live cookie 残留」路径（用户手清存储/ITP 分区）不触发 clearCredentials，cookie 残留至
      7 天自然过期；影响仅为该浏览器后续访问受保护页「骨架→弹窗」而非直接 307，终态等价无安全影响。
      可选收口点：auth-context 挂载时检测无 RT 顺手清提示 cookie。登记日期 2026-08-30。
- [ ] **c_rt_live 生产 Secure 属性评估**（N2 审核 Finding 2）：值恒 `=1` 无敏感信息，dev http 无法
      无条件追加；生产 https 部署时条件性补 `Secure`。登记日期 2026-08-30。
