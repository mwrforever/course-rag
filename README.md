# RAG 课程助手（commerce-customer）

企业级多模态 RAG 课程助手：C 端学生 AI 对话（意图理解 → 混合检索 → 生成，SSE 流式）+ B 端知识库与课程管理。
核心链路：ETL 多模态入库 → Agent 图编排 → SSE 流式对话，叠加偏好与情景记忆体系。

技术栈：Java 17 / Spring Boot 3.5 / Spring AI Alibaba 1.1.2 + PostgreSQL / Redis / Milvus / MinIO；
C 端 Next.js 15（App Router）+ B 端 Vue 3.5，pnpm workspace 单仓管理。

工程规范与数据契约见 [AGENTS.md](AGENTS.md)（项目最高规范）、功能设计见 `docs/superpowers/specs/`（仅本地）、
变更记录见 [CHANGELOG.md](CHANGELOG.md)、待办登记见 [TASK.md](TASK.md)。

## 示例数据（docs/data）

`docs/data/` 是 **RAG 知识库示例数据**：供本地 ETL 解析入库、分块与检索效果验证使用的第三方采集题库，
按 13 个分类组织、共 64 个主题（128 个文件，约 65MB）。每个主题由两个文件成对组成：

| 文件 | 内容 |
|------|------|
| `{主题}_bank.json` | 采集的原始题库：顶层含 `source` / `group` / `tag_id` / `fetched_at` / `total_unique` 等采集元信息，`questions[]` 为题目（题干、选项、正确答案、解析） |
| `{主题}_qa.xlsx` | 同一批题目的结构化导出表：题号 / 题型 / 题目 / 选项 / 正确答案 / 解析 |

| 分类 | 主题 |
|------|------|
| devops | gitlab |
| 中间件 | Hadoop、Hive、Kafka、Redis、ZooKeeper |
| 人工智能 | Agent、RAG、大模型开发、大模型概念、微调、推理优化、提示词工程、数据挖掘、机器学习基础、概率论与数理统计、深度学习基础、线性代数、计算机视觉 |
| 基础算法 | 哈希、复杂度、排序、查找、递归 |
| 安全·测试 | 加密和安全、软件测试 |
| 推荐 | Prompt判断 |
| 数据分析 | Python分析库、数据思维 |
| 数据结构 | 图、堆、字符串、数组、栈、树、链表、队列 |
| 框架技术 | React、Spring、Vue |
| 移动端开发 | Android、iOS |
| 编程语言 | C++、CSS、C语言、Go、HTML、Java、Javascript、Kotlin、Python、Swift |
| 行测 | 判断推理、常识判断、数学运算、资料分析 |
| 计算机基础 | Linux、SQL、操作系统、数据库、编译和体系结构、网络基础、设计模式、软件工程 |

> `docs/` 下其余目录（`bugs/` `pref/` `progress/` `prompt/` `design/` `contracts/` `superpowers/` `agmds-research/`）
> 为本地过程文档，不入库；仅 `docs/data/` 随仓库分发。
