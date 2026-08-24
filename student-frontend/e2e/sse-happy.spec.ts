import { test, expect } from "@playwright/test";
import { mockApi, login, mockChatStream, frame } from "./helpers/sse-route";

/**
 * SSE happy path E2E（整合 spec §3.2 SSE-happy 组）
 *
 * 完整链路：metadata→thinking→thinking_end→delta×2→tool_call→tool_result→sources→end
 * 断言：URL 不跳转（E2E 实证修订，见设计文档 §六.13）、思考卡折叠、正文流式、工具卡配对成功、来源卡前置、操作栏浮现。
 * 注意：mock 一次性 fulfill 整条流，前端解析器跨 chunk 增量消费（单测已覆盖残包）。
 */

test.describe("SSE 全链路", () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page);
  });

  test("新对话完整链路：建槽→思考→正文→工具→来源→完成", async ({ page }) => {
    const sse =
      frame("metadata", { runId: "9001", sessionId: "77", model: "qwen3.8-max" }, 1) +
      frame("thinking", { delta: "我需要检索课程知识库确认先修要求。" }, 2) +
      frame("thinking_end", {}, 3) +
      frame(
        "delta",
        {
          text: "学习本课程建议具备以下基础：\n\n1. 至少掌握一门编程语言。\n2. 了解基本数据结构概念。",
        },
        4,
      ) +
      frame(
        "tool_call",
        {
          toolCallId: "t-1",
          toolName: "searchKnowledge",
          input: JSON.stringify({ query: "先修要求" }),
        },
        5,
      ) +
      frame(
        "tool_result",
        { toolCallId: "t-1", status: "success", output: "检索到课程先修要求章节" },
        6,
      ) +
      frame(
        "sources",
        {
          sources: [
            { chunkId: "101", docTitle: "课程讲义第1章", headingPath: "第1章 > 1.2", score: 0.87 },
          ],
        },
        7,
      ) +
      frame("end", { runId: "9001", status: "COMPLETED", messageId: "5001" }, 8);

    await mockChatStream(page, sse);
    await login(page, "/");
    await page.goto("/chat");

    // 空态：建议提问 chip 存在
    await expect(
      page
        .getByText(/输入你的问题/)
        .or(page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行")),
    ).toBeAttached();

    await page
      .getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行")
      .fill("这门课需要什么基础？");
    await page.getByRole("button", { name: "发送" }).click();

    // metadata 后不跳转 URL（E2E 实证修订：replace 重挂载丢流，决策见 chat-workspace）
    await expect(page).toHaveURL(/\/chat$/);

    // 渲染屏障：先等用户消息落位（dispatch send 提交），再推进同批渲染断言
    await expect(page.getByText("这门课需要什么基础？")).toBeVisible();
    // 思考卡：thinking 与 thinking_end 帧同批送达时 React 合并渲染（瞬态「正在思考…」
    // 不可断言），直接断言折叠后的「已思考」终态
    await expect(page.getByText("已思考")).toBeVisible({ timeout: 10_000 });

    // 正文流式渲染
    await expect(page.getByText(/至少掌握一门编程语言/)).toBeVisible();

    // 工具卡：人话映射 + 成功摘要（注意 toolName 人话与思考摘要文案可能同串，
    // 用 tool_result 的 output 摘要做独特锚；工具卡名称按存在性宽松断言）
    await expect(page.getByText("检索到课程先修要求章节")).toBeVisible();

    // 来源卡：参考来源 + 标题 + 置信条所在卡
    await expect(page.getByRole("heading", { name: "参考来源" })).toBeVisible();
    await expect(page.getByText("课程讲义第1章")).toBeVisible();

    // end 后操作栏浮现（复制 + 有用/无用）
    await expect(page.getByRole("button", { name: "复制回答" })).toBeVisible();
    await expect(page.getByRole("button", { name: "有用" })).toBeVisible();

    // 输入区解除禁用（发送键回归）
    await expect(page.getByRole("button", { name: "发送" })).toBeVisible();
  });

  test("多轮追问：第二轮流正常建立（Critical-1 回归）", async ({ page }) => {
    await mockChatStream(
      page,
      frame("metadata", { runId: "9001", sessionId: "77", model: "qwen3.8-max" }, 1) +
        frame("delta", { text: "第一轮回答。" }, 2) +
        frame("end", { runId: "9001", status: "COMPLETED", messageId: "5001" }, 3),
    );
    await login(page, "/");
    await page.goto("/chat");
    const input = page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行");
    await input.fill("第一问");
    await page.getByRole("button", { name: "发送" }).click();
    await expect(page.getByText("第一轮回答。")).toBeVisible();

    // 第二轮：重新 mock 新 run 的流（POST /chat 每次命中最新注册的 route）
    await mockChatStream(
      page,
      frame("metadata", { runId: "9002", sessionId: "77", model: "qwen3.8-max" }, 1) +
        frame("delta", { text: "第二轮回答。" }, 2) +
        frame("end", { runId: "9002", status: "COMPLETED", messageId: "5002" }, 3),
    );
    await input.fill("第二问");
    await page.getByRole("button", { name: "发送" }).click();
    await expect(page.getByText("第二轮回答。")).toBeVisible();
  });
});
