# CHANGELOG.md — 工程变更记录

> 追加式记录：每次修订先在此追加（日期 · 变更简述 · 原因），再改正文。

---

- 2026-08-25 · 宪法重构：基于 7 主题技术栈调研（`docs/agmds-research/2026-08-25-*.md`，Java+Spring Boot / MyBatis-Plus+PG / Redis+MinIO / Milvus / Spring AI Alibaba Agent / 双前端 / 构建测试 CI）按四段结构重写项目宪法，发布为 `AGENTS.md.draft` 待用户确认后替换；配套新增 CHANGELOG.md（本文件首建）；TASK.md 追加 §5 调研不可得项登记表。原因：constitution-generator 流程生成更完整、可溯源（官方文档依据）的工程规范。
- 2026-08-25 · 宪法草案审核修复（审核专员 2 轮）：Maven 本地仓库路径实测修正为 `D:/code/java/maven/apache-maven-3.9.16/repository`（旧记忆路径作废，见记忆索引）；Part C 补用户拍板前置条款（C 端界面落地前必须与用户沟通）；D.3 启动类名更正为 CommerceRagApplication；TASK.md §5 补 E-6 登记（36 项）。原因：constitution-generator §四.5 审核轮发现并修复。
- 2026-08-25 · 用户批准定稿：`AGENTS.md.draft` 替换为 `AGENTS.md` 生效（旧版备份 `AGENTS.md.bak.2026-08-25`，git 历史亦保有旧版）。
