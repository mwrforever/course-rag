import { test, expect } from "@playwright/test";
import { mockApi, login, mockChatStream, frame, heartbeat } from "./helpers/sse-route";

/**
 * SSE 11 事件逐类断言（整合 spec §3.2 SSE-事件逐类 组，TASK.md §2 硬性要求；
 * 2026-08-28 时间线改版：thinking/query_plan/stage 断言对齐链式时间轴 testid）
 *
 * 原则：每类事件一个最小用例：先推目标事件帧，再以 end 收尾
 * （避免 EOF 未终态触发重连退避链，保证用例独立可并行）。
 */

test.describe("SSE 事件逐类", () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page);
  });

  test("metadata：建消息槽（新对话不跳转 URL，E2E 实证修订）", async ({ page }) => {
    await mockChatStream(
      page,
      frame("metadata", { runId: "9001", sessionId: "77", model: "qwen3.8-max" }, 1) +
        frame("end", { runId: "9001", status: "COMPLETED", messageId: "5001" }, 2),
    );
    await login(page, "/");
    await page.goto("/chat");
    await page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行").fill("你好");
    await page.getByRole("button", { name: "发送" }).click();
    // 修订：replace 会重挂载丢流，产品决策不跳转（见 chat-workspace 注释）
    await expect(page).toHaveURL(/\/chat$/);
    // 消息槽建立 + model 徽标渲染（metadata 元信息承载）
    await expect(page.getByText("qwen3.8-max")).toBeVisible();
    await expect(page.getByTestId("model-badge")).toBeVisible();
  });

  test("thinking：时间轴思考步骤渲染，默认收起（末行可见）+ 帧同批收敛「思考已完成」", async ({
    page,
  }) => {
    await mockChatStream(
      page,
      frame("metadata", { runId: "9001", sessionId: "77", model: "qwen3.8-max" }, 1) +
        frame("thinking", { delta: "正在检索课程资料……", stage: "understanding" }, 2) +
        frame("end", { runId: "9001", status: "COMPLETED", messageId: "5001" }, 3),
    );
    await login(page, "/");
    await page.goto("/chat");
    await page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行").fill("提问");
    await page.getByRole("button", { name: "发送" }).click();
    // 2026-08-28 时间线改版：思考经链式时间轴 thinking-step 渲染（推理卡已删）；
    // 帧同批送达时 end 已收敛为完成态（瞬态「思考中」不可断言）
    await expect(page.getByTestId("chain-timeline")).toBeVisible();
    await expect(page.getByTestId("thinking-step")).toBeVisible();
    await expect(page.getByTestId("thinking-status")).toHaveText("思考已完成");
    // 收起态思考体锚定底部：末行落入 26px 可视窗（DOM 含全部思考行）
    await expect(page.getByTestId("thinking-body")).toContainText("正在检索课程资料");
  });

  test("thinking_end：思考步骤收敛为「思考已完成」（默认收起）", async ({ page }) => {
    await mockChatStream(
      page,
      frame("metadata", { runId: "9001", sessionId: "77", model: "qwen3.8-max" }, 1) +
        frame("thinking", { delta: "思考内容", stage: "understanding" }, 2) +
        frame("thinking_end", { stage: "understanding" }, 3) +
        frame("end", { runId: "9001", status: "COMPLETED", messageId: "5001" }, 4),
    );
    await login(page, "/");
    await page.goto("/chat");
    await page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行").fill("提问");
    await page.getByRole("button", { name: "发送" }).click();
    await expect(page.getByTestId("thinking-status")).toHaveText("思考已完成");
  });

  test("query_plan：查询计划步骤渲染意图标签与改写查询（2026-08-28 时间线改版）", async ({
    page,
  }) => {
    await mockChatStream(
      page,
      frame("metadata", { runId: "9001", sessionId: "77", model: "qwen3.8-max" }, 1) +
        frame(
          "query_plan",
          {
            intent: "knowledge_question",
            rewritten: ["什么是倒排索引"],
            filters: { courseNames: ["数据结构与算法"] },
          },
          2,
        ) +
        frame("delta", { text: "改写后的回答正文。" }, 3) +
        frame("end", { runId: "9001", status: "COMPLETED", messageId: "5001" }, 4),
    );
    await login(page, "/");
    await page.goto("/chat");
    await page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行").fill("提问");
    await page.getByRole("button", { name: "发送" }).click();
    // 查询计划步骤：意图标签（人话映射）+ 首条改写查询
    await expect(page.getByTestId("query-plan-step")).toBeVisible();
    await expect(page.getByTestId("query-plan-intent")).toHaveText("知识问答");
    await expect(page.getByTestId("query-plan-rewritten-first")).toContainText("什么是倒排索引");
    await expect(page.getByText("改写后的回答正文。")).toBeVisible();
  });

  test("stage-thinking：阶段步骤与思考步骤按到达序挂链（stage → thinking）", async ({ page }) => {
    await mockChatStream(
      page,
      frame("metadata", { runId: "9001", sessionId: "77", model: "qwen3.8-max" }, 1) +
        frame("stage", { stage: "understanding", label: "正在理解你的问题" }, 2) +
        frame("thinking", { delta: "分析提问意图", stage: "understanding" }, 3) +
        frame("thinking_end", { stage: "understanding" }, 4) +
        frame("end", { runId: "9001", status: "COMPLETED", messageId: "5001" }, 5),
    );
    await login(page, "/");
    await page.goto("/chat");
    await page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行").fill("提问");
    await page.getByRole("button", { name: "发送" }).click();
    // 阶段步骤（OpStep）与思考步骤同链：阶段在前、思考在后（到达序）
    const stageText = page.getByTestId("op-step-text").filter({ hasText: "正在理解你的问题" });
    await expect(stageText).toBeVisible();
    const thinking = page.getByTestId("thinking-step");
    await expect(thinking).toBeVisible();
    const stageBox = await stageText.boundingBox();
    const thinkingBox = await thinking.boundingBox();
    expect(stageBox && thinkingBox && stageBox.y < thinkingBox.y).toBeTruthy();
    await expect(page.getByTestId("thinking-status")).toHaveText("思考已完成");
  });

  test("delta：正文增量渲染", async ({ page }) => {
    await mockChatStream(
      page,
      frame("metadata", { runId: "9001", sessionId: "77", model: "qwen3.8-max" }, 1) +
        frame("delta", { text: "这是流式回答的正文内容。" }, 2) +
        frame("end", { runId: "9001", status: "COMPLETED", messageId: "5001" }, 3),
    );
    await login(page, "/");
    await page.goto("/chat");
    await page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行").fill("提问");
    await page.getByRole("button", { name: "发送" }).click();
    await expect(page.getByText("这是流式回答的正文内容。")).toBeVisible();
  });

  test("tool_call：工具卡出现（pending 态）", async ({ page }) => {
    await mockChatStream(
      page,
      frame("metadata", { runId: "9001", sessionId: "77", model: "qwen3.8-max" }, 1) +
        frame("tool_call", { toolCallId: "t-1", toolName: "searchKnowledge", input: "{}" }, 2) +
        frame("end", { runId: "9001", status: "COMPLETED", messageId: "5001" }, 3),
    );
    await login(page, "/");
    await page.goto("/chat");
    await page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行").fill("提问");
    await page.getByRole("button", { name: "发送" }).click();
    await expect(page.getByText("检索课程知识库")).toBeVisible();
  });

  test("tool_result：按 toolCallId 配对转成功态", async ({ page }) => {
    await mockChatStream(
      page,
      frame("metadata", { runId: "9001", sessionId: "77", model: "qwen3.8-max" }, 1) +
        frame("tool_call", { toolCallId: "t-1", toolName: "searchKnowledge", input: "{}" }, 2) +
        frame(
          "tool_result",
          { toolCallId: "t-1", status: "success", output: "命中课程讲义第1章" },
          3,
        ) +
        frame("end", { runId: "9001", status: "COMPLETED", messageId: "5001" }, 4),
    );
    await login(page, "/");
    await page.goto("/chat");
    await page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行").fill("提问");
    await page.getByRole("button", { name: "发送" }).click();
    await expect(page.getByText("命中课程讲义第1章")).toBeVisible();
  });

  test("sources：来源卡渲染于正文之前", async ({ page }) => {
    await mockChatStream(
      page,
      frame("metadata", { runId: "9001", sessionId: "77", model: "qwen3.8-max" }, 1) +
        frame(
          "sources",
          {
            sources: [
              {
                chunkId: "101",
                docTitle: "课程讲义第1章",
                headingPath: "第1章 > 1.2",
                score: 0.87,
              },
            ],
          },
          2,
        ) +
        frame("delta", { text: "正文内容" }, 3) +
        frame("end", { runId: "9001", status: "COMPLETED", messageId: "5001" }, 4),
    );
    await login(page, "/");
    await page.goto("/chat");
    await page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行").fill("提问");
    await page.getByRole("button", { name: "发送" }).click();
    // 2026-08-28 时间线改版：来源经检索步骤（sources-step，到达即完成态）承载，
    // 位于正文之前，点击打开召回抽屉
    const trigger = page.getByTestId("sources-step");
    await expect(trigger).toBeVisible();
    await expect(trigger).toContainText("已检索 1 篇相关资料");
    const body = page.getByText("正文内容");
    const triggerBox = await trigger.boundingBox();
    const bodyBox = await body.boundingBox();
    expect(triggerBox && bodyBox && triggerBox.y < bodyBox.y).toBeTruthy();
    await trigger.click();
    await expect(page.getByTestId("retrieval-drawer")).toBeVisible();
    await expect(page.getByText("课程讲义第1章")).toBeVisible();
  });

  test("stage：时间轴阶段步骤显示「知识库查询中」等文案（2026-08-28 时间线改版）", async ({
    page,
  }) => {
    await mockChatStream(
      page,
      frame("metadata", { runId: "9001", sessionId: "77", model: "qwen3.8-max" }, 1) +
        frame("stage", { stage: "understanding", label: "正在理解你的问题" }, 2) +
        frame("stage", { stage: "retrieving", label: "知识库查询中" }, 3) +
        frame("delta", { text: "阶段之后的正文。" }, 4) +
        frame("end", { runId: "9001", status: "COMPLETED", messageId: "5001" }, 5),
    );
    await login(page, "/");
    await page.goto("/chat");
    await page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行").fill("提问");
    await page.getByRole("button", { name: "发送" }).click();
    // 2026-08-28 时间线改版：阶段经链式时间轴 OpStep 直接平铺（推理卡已删，
    // 无展开交互）；两个阶段按到达序挂链且位于正文之前
    const first = page.getByTestId("op-step-text").filter({ hasText: "正在理解你的问题" });
    const second = page.getByTestId("op-step-text").filter({ hasText: "知识库查询中" });
    await expect(first).toBeVisible();
    await expect(second).toBeVisible();
    const body = page.getByText("阶段之后的正文。");
    const firstBox = await first.boundingBox();
    const secondBox = await second.boundingBox();
    const bodyBox = await body.boundingBox();
    expect(firstBox && secondBox && firstBox.y < secondBox.y).toBeTruthy();
    expect(secondBox && bodyBox && secondBox.y < bodyBox.y).toBeTruthy();
  });

  test("error：run 级错误横幅 + 重试按钮", async ({ page }) => {
    await mockChatStream(
      page,
      frame("metadata", { runId: "9001", sessionId: "77", model: "qwen3.8-max" }, 1) +
        frame("error", { runId: "9001", status: "ERROR", message: "模型服务超时" }, 2),
    );
    await login(page, "/");
    await page.goto("/chat");
    await page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行").fill("提问");
    await page.getByRole("button", { name: "发送" }).click();
    await expect(page.getByText("模型服务超时")).toBeVisible();
    await expect(page.getByRole("button", { name: "重试" })).toBeVisible();
  });

  test("end：终态后操作栏浮现（messageId 就绪）", async ({ page }) => {
    await mockChatStream(
      page,
      frame("metadata", { runId: "9001", sessionId: "77", model: "qwen3.8-max" }, 1) +
        frame("delta", { text: "回答内容" }, 2) +
        frame("end", { runId: "9001", status: "COMPLETED", messageId: "5001" }, 3),
    );
    await login(page, "/");
    await page.goto("/chat");
    await page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行").fill("提问");
    await page.getByRole("button", { name: "发送" }).click();
    await expect(page.getByRole("button", { name: "复制回答" })).toBeVisible();
    await expect(page.getByRole("button", { name: "发送" })).toBeVisible();
  });

  test("heartbeat：注释行不破坏解析，流继续渲染", async ({ page }) => {
    await mockChatStream(
      page,
      heartbeat() +
        frame("metadata", { runId: "9001", sessionId: "77", model: "qwen3.8-max" }, 1) +
        heartbeat() +
        frame("delta", { text: "心跳之后的正文。" }, 2) +
        frame("end", { runId: "9001", status: "COMPLETED", messageId: "5001" }, 3),
    );
    await login(page, "/");
    await page.goto("/chat");
    await page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行").fill("提问");
    await page.getByRole("button", { name: "发送" }).click();
    await expect(page.getByText("心跳之后的正文。")).toBeVisible();
    // 无异常横幅（data-testid 精确断言，规避 Next 路由播报器的 role=alert 干扰）
    await expect(page.getByTestId("stream-error-banner")).toBeHidden();
  });
});
