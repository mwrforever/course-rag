# 取消回滚 checkpoint 类型破坏：Jackson 反序列化丢失 Message 类型

- **风险类别**：运行错误（checkpoint 损坏，后续轮次对话崩溃或消息不落库）
- **严重度**：P1（取消 + 非首次轮即触发）
- **变更范围**：未提交工作区全部新增代码

## 证据

1. `ChatRequestWorker.java:481-493`（深拷贝经泛型 Map 往返，丢失 Message 子类型）：
   ```java
   String json = objectMapper.writeValueAsString(state);
   return objectMapper.readValue(json, Map.class);   // messages 的 List<Message> → List<LinkedHashMap>
   ```
   `ObjectMapper` 默认配置**未注册** Spring AI `Message` 子类（UserMessage/AssistantMessage/ToolResponseMessage）的 Jackson 多态类型，反序列化后元素类型为 `LinkedHashMap`。

2. `ChatRequestWorker.java:734-751`（把类型已毁的 state 写入 checkpoint）：
   ```java
   Checkpoint newCp = Checkpoint.builder()
           .id(UUID.randomUUID().toString())
           .state(snapshot.state())          // List<LinkedHashMap> 的 messages
           .nodeId(snapshot.nodeId())
           .nextNodeId(snapshot.nextNodeId())
           .build();
   saver.put(config, newCp);
   ```

3. 下游消费点：
   - `LeadAgentGraph.java:150-152`（图节点读取）：`(List<Message>) overAllState.value("messages")` 强转 → ClassCastException；
   - `ChatRequestWorker.java:535`：`item instanceof Message` 全部静默跳过 → 消息不落库。

## 触发路径与影响

会话第 2 轮起（已有历史 checkpoint）执行取消 → `handleCancelled`（:706-724）→ `rollbackCheckpoint` 将含 `LinkedHashMap` 元素的 state 写入 checkpoint → 该 session 下一次 run 从 checkpoint 恢复：图节点强转崩溃（500/run ERROR）或 `persistMessages` 静默跳过全部消息（数据丢失）。

**置信度说明**：类型丢失本身确定（Jackson 无多态注册）。下游崩溃程度依赖 SAA 1.1.2.0 的 checkpoint 恢复序列化细节，本地 Maven 仓库无 `spring-ai-alibaba-graph-core` jar 可进一步实证；建议修复时做一次真实环境验证。

## 建议修复方向

- `deepCopyState` 改用 Spring AI 的 `MessageType` 感知序列化（或注册 `@JsonTypeInfo` 多态）；
- 或回滚时不写全量 state，仅恢复 `pendingWrites`（与设计文档 §3.4"恢复 pendingWrites"的原始意图一致）；
- 或取消后删除该 session 的最新 checkpoint 让下轮从干净状态重建。
