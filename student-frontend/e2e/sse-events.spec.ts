import { test, expect } from "@playwright/test";
import { mockApi, login, mockChatStream, frame, heartbeat } from "./helpers/sse-route";

/**
 * SSE 事件逐类断言（整合 spec §3.2 SSE-事件逐类 组，TASK.md §2 硬性要求；
 * 2026-08-28 时间线改版：thinking/query_plan/stage 断言对齐链式时间轴 testid；
 * 2026-08-30 对齐设计稿：query_plan/stage 帧前端忽略——「未识别意图」「正在生成回答」
 * 不再渲染，断言改为「不渲染」负向校验）
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

  test("query_plan（2026-08-30 对齐设计稿）：帧被忽略，不渲染查询计划步骤，正文正常", async ({
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
    // 2026-08-30：重写正文/意图胶囊不回前端（数据仍落库供审计），查询计划步骤不渲染
    await expect(page.getByTestId("query-plan-step")).toHaveCount(0);
    await expect(page.getByText("改写后的回答正文。")).toBeVisible();
  });

  test("stage+thinking（2026-08-30 对齐设计稿）：stage 帧被忽略，思考步骤照常渲染", async ({
    page,
  }) => {
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
    // 2026-08-30：阶段文案（「正在理解你的问题」等）不再渲染；思考步骤照常
    await expect(page.getByText("正在理解你的问题")).toHaveCount(0);
    const thinking = page.getByTestId("thinking-step");
    await expect(thinking).toBeVisible();
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

  test("stage（2026-08-30 对齐设计稿）：阶段帧被忽略，「知识库查询中」等文案不再渲染", async ({
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
    // 2026-08-30 对齐设计稿：阶段步骤（含「正在生成回答」文案）不再渲染，正文照常
    await expect(page.getByText("正在理解你的问题")).toHaveCount(0);
    await expect(page.getByText("知识库查询中")).toHaveCount(0);
    await expect(page.getByText("阶段之后的正文。")).toBeVisible();
  });

  test("检索时序（2026-09-03 拍板）：sources 帧迟到（晚于思考/正文帧到达）→ 按到达序渲染在思考之后（不前置）", async ({
    page,
  }) => {
    // route-mock 模拟服务端主保证失效的最坏形态：sources 帧晚于 thinking/delta 到达——
    // 2026-09-03 用户拍板「按时间触发顺序渲染」：渲染层不再重排（原 M3 前置兜底下线），
    // 迟到 sources 按到达序落在思考之后（web-first 断言 DOM 顺序）
    await mockChatStream(
      page,
      frame("metadata", { runId: "9001", sessionId: "77", model: "qwen3.8-max" }, 1) +
        frame("thinking", { delta: "组织回答中", stage: "generating" }, 2) +
        frame("delta", { text: "引用资料的回答正文。" }, 3) +
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
          4,
        ) +
        frame("end", { runId: "9001", status: "COMPLETED", messageId: "5001" }, 5),
    );
    await login(page, "/");
    await page.goto("/chat");
    await page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行").fill("提问");
    await page.getByRole("button", { name: "发送" }).click();
    // 步骤就绪后按几何位置断言：thinking-step 在前、迟到 sources-step 在后（到达序）
    const sourcesStep = page.getByTestId("sources-step");
    await expect(sourcesStep).toBeVisible();
    const thinkingStep = page.getByTestId("thinking-step");
    await expect(thinkingStep).toBeVisible();
    const sourcesBox = await sourcesStep.boundingBox();
    const thinkingBox = await thinkingStep.boundingBox();
    expect(sourcesBox && thinkingBox && thinkingBox.y < sourcesBox.y).toBeTruthy();
    // 正文照常渲染于时间轴之后（链式结构完整性）
    await expect(page.getByText("引用资料的回答正文。")).toBeVisible();
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
