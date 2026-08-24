import { test, expect } from "@playwright/test";
import { mockApi, login, mockChatStream, frame, heartbeat } from "./helpers/sse-route";

/**
 * SSE 10 事件逐类断言（整合 spec §3.2 SSE-事件逐类 组，TASK.md §2 硬性要求）
 *
 * 原则：每类事件一个最小用例——先推目标事件帧，再以 end 收尾
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
  });

  test("thinking：思考卡展开并流式追加文本", async ({ page }) => {
    await mockChatStream(
      page,
      frame("metadata", { runId: "9001", sessionId: "77", model: "qwen3.8-max" }, 1) +
        frame("thinking", { delta: "正在检索课程资料……" }, 2) +
        frame("end", { runId: "9001", status: "COMPLETED", messageId: "5001" }, 3),
    );
    await login(page, "/");
    await page.goto("/chat");
    await page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行").fill("提问");
    await page.getByRole("button", { name: "发送" }).click();
    await expect(page.getByText("正在思考…")).toBeVisible();
    await expect(page.getByText("正在检索课程资料……")).toBeVisible();
  });

  test("thinking_end：思考卡折叠为「已思考」", async ({ page }) => {
    await mockChatStream(
      page,
      frame("metadata", { runId: "9001", sessionId: "77", model: "qwen3.8-max" }, 1) +
        frame("thinking", { delta: "思考内容" }, 2) +
        frame("thinking_end", {}, 3) +
        frame("end", { runId: "9001", status: "COMPLETED", messageId: "5001" }, 4),
    );
    await login(page, "/");
    await page.goto("/chat");
    await page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行").fill("提问");
    await page.getByRole("button", { name: "发送" }).click();
    await expect(page.getByText("已思考")).toBeVisible();
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
    await expect(page.getByRole("heading", { name: "参考来源" })).toBeVisible();
    await expect(page.getByText("课程讲义第1章")).toBeVisible();
    // 来源卡位于正文之前（DOM 顺序断言）
    const sources = page.getByRole("heading", { name: "参考来源" });
    const body = page.getByText("正文内容");
    await expect(sources).toBeVisible();
    const sourcesBox = await sources.boundingBox();
    const bodyBox = await body.boundingBox();
    expect(sourcesBox && bodyBox && sourcesBox.y < bodyBox.y).toBeTruthy();
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
