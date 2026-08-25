# CHANGELOG.md — 工程变更记录

> 追加式记录：每次修订先在此追加（日期 · 变更简述 · 原因），再改正文。

---

- 2026-08-25 · 双前端全量 UI 重构（PR#7 C 端 / PR#8 B 端）：C 端 kimi 蓝系设计体系（暖米白底 #FBFAF9 + 品牌蓝 #2F8BF5 族）、首页电商风（分类筛选 + 滚动动效）、课程助手 kimi 式全局左栏（独立 (chat) 路由组 + 会话历史侧栏 + 大圆角输入区）；B 端深色侧栏现代管理风格（ink 石墨蓝 + 图标分组导航 + 面包屑 + 路由过渡）。原因：用户判定原界面「奇丑无比」且 C 端几乎不可用，责令全面重构；课程助手以 kimi 设计稿为参照，B 端侧栏层级与职责（学生/教师拆分、课程五子页、404、知识库入栏）按用户拍板落地；修复 RouterView+Transition 缺 :key 内容区空白、双重容器、分组展开模型反转等实证缺陷；测试与 E2E 全量同步（C 端 358 单测+29 E2E / B 端 278 单测+23 E2E，核心文件 100% 覆盖铁律保持）。
- 2026-08-25 · 宪法重构：基于 7 主题技术栈调研（`docs/agmds-research/2026-08-25-*.md`，Java+Spring Boot / MyBatis-Plus+PG / Redis+MinIO / Milvus / Spring AI Alibaba Agent / 双前端 / 构建测试 CI）按四段结构重写项目宪法，发布为 `AGENTS.md.draft` 待用户确认后替换；配套新增 CHANGELOG.md（本文件首建）；TASK.md 追加 §5 调研不可得项登记表。原因：constitution-generator 流程生成更完整、可溯源（官方文档依据）的工程规范。
- 2026-08-25 · 宪法草案审核修复（审核专员 2 轮）：Maven 本地仓库路径实测修正为 `D:/code/java/maven/apache-maven-3.9.16/repository`（旧记忆路径作废，见记忆索引）；Part C 补用户拍板前置条款（C 端界面落地前必须与用户沟通）；D.3 启动类名更正为 CommerceRagApplication；TASK.md §5 补 E-6 登记（36 项）。原因：constitution-generator §四.5 审核轮发现并修复。
- 2026-08-25 · 用户批准定稿：`AGENTS.md.draft` 替换为 `AGENTS.md` 生效（旧版备份 `AGENTS.md.bak.2026-08-25`，git 历史亦保有旧版）。
- 2026-08-25 · 默认管理员账户改配置驱动：新增 `properties/AdminSeedProperties`（`auth.admin-seed`，默认 admin/admin123，env `AUTH_ADMIN_SEED_*` 可覆盖）+ `config/AdminSeedInitializer`（ApplicationRunner，非 @PostConstruct——顺时序无竞态）+ `ISysUserService#ensureSeedSuperAdmin` 幂等写入（无超管则创建；密码仍为出厂默认则刷新为配置值，env 覆盖生效；已改密绝不覆盖）。原因：V6 迁移凭证硬编码且迁移冻结不可改，凭证改由配置挂载、启动动态注入；配 9 个新单测（seed 五分支 + 属性绑定 + 初始化器委托）。
