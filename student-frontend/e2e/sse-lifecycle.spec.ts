import { test, expect } from "@playwright/test";
import { mockApi, login, mockChatStream, frame } from "./helpers/sse-route";

/**
 * SSE 生命周期 E2E（整合 spec §3.2：cancel / 409 / reconnect / REPLAY_FAILED）
 *
 * - cancel：发送后按钮 morph「停止生成」→ 点击 → POST {runId}/cancel 请求到达
 * - 409：二次发送冲突 → toast「当前会话正在回答中」
 * - reconnect：EOF 未终态触发断流路径（Task11 修复：EOF 即断流，无需等 30s）→
 *   GET reconnect?lastEventId= 携带锚点 → 续流渲染
 * - REPLAY_FAILED：重连返回 error 帧 → 「重新提问」引导
 * （删除 409 语义随 /sessions 页下线迁移至侧边栏删除路径，由 chat-sidebar E2E/单测覆盖）
 */

test.describe("SSE 生命周期", () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page);
  });

  test("取消生成：停止按钮 morph + cancel 请求发出", async ({ page }) => {
    // 时序说明：send 为非乐观确认（postChat resolve 后才置 streaming），mock 单次
    // 交付下「流进行中」的 morph 窗口不可达（实验坐实）。本用例利用断流语义制造
    // 确定性等待窗口：第一段流（delay 300ms 保证 streaming 状态提交）无终态 EOF
    // → 触发 runReconnect → reconnect 挂起 3s 交付续帧：挂起期间 streaming=true
    // 稳定，停止按钮可点；点击后 cancel 请求独立发出（断言），随后续帧落位。
    await mockChatStream(
      page,
      frame("metadata", { runId: "9001", sessionId: "77", model: "qwen3.8-max" }, 1) +
        frame("thinking", { delta: "长思考" }, 2) +
        frame("delta", { text: "部分回答" }, 3),
      { delayMs: 300 },
    );
    // reconnect 挂起 3s 提供稳定 morph 窗口（stopButton 可见期内点击）
    await page.route("**/api/v1/student/chat/9001/reconnect*", async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 3000));
      await route.fulfill({
        status: 200,
        headers: { "Content-Type": "text/event-stream;charset=UTF-8" },
        body:
          frame("thinking_end", {}, 4) +
          frame("delta", { text: "续流完整回答" }, 5) +
          frame("end", { runId: "9001", status: "COMPLETED", messageId: "5001" }, 6),
      });
    });
    // cancel 端点 mock 200（幂等）
    let cancelCalled = 0;
    await page.route("**/api/v1/student/chat/9001/cancel", async (route) => {
      cancelCalled += 1;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ code: 0, message: "success", data: null }),
      });
    });

    await login(page, "/");
    await page.goto("/chat");
    await page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行").fill("提问");
    await page.getByRole("button", { name: "发送" }).click();
    // morph：发送键变停止键（reconnect 挂起窗口内稳定可见）
    const stopButton = page.getByRole("button", { name: "停止生成" });
    await expect(stopButton).toBeVisible({ timeout: 8_000 });
    await stopButton.click();
    // cancel 请求到达（幂等端点，无等待依赖）
    await expect.poll(() => cancelCalled).toBeGreaterThan(0);
  });

  test("409 并发冲突：toast「当前会话正在回答中」", async ({ page }) => {
    await mockChatStream(
      page,
      frame("metadata", { runId: "9001", sessionId: "77", model: "q" }, 1),
      {
        status: 409,
      },
    );
    await login(page, "/");
    await page.goto("/chat");
    await page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行").fill("提问");
    await page.getByRole("button", { name: "发送" }).click();
    await expect(page.getByText("当前会话正在回答中")).toBeVisible();
  });

  test("断流重连：EOF 未终态触发重连并携带 lastEventId", async ({ page }) => {
    // 时序说明：EOF 判定在 hook 内先让出 50ms 宏任务（渲染提交窗口），保证
    // stateRef.streaming 已提交再触发 runReconnect（瞬时流漏重连缺陷已修复，
    // 见 use-chat-stream.ts EOF 分支注释）；断流即走 reconnect（第 1 次尝试无退避）
    await mockChatStream(
      page,
      frame("metadata", { runId: "9001", sessionId: "77", model: "qwen3.8-max" }, 1) +
        frame("thinking", { delta: "思考" }, 2) +
        frame("delta", { text: "部分回答" }, 3),
    );
    // reconnect 续帧（含 end）：捕获请求断言 lastEventId=3
    let reconnectUrl: string | null = null;
    await page.route("**/api/v1/student/chat/9001/reconnect*", async (route) => {
      reconnectUrl = route.request().url();
      await route.fulfill({
        status: 200,
        headers: { "Content-Type": "text/event-stream;charset=UTF-8" },
        body:
          frame("thinking_end", {}, 4) +
          frame("delta", { text: "续流后的完整回答" }, 5) +
          frame("end", { runId: "9001", status: "COMPLETED", messageId: "5001" }, 6),
      });
    });

    await login(page, "/");
    await page.goto("/chat");
    await page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行").fill("提问");
    await page.getByRole("button", { name: "发送" }).click();

    // 渲染屏障：第一段流消费完成（EOF 判定 50ms 窗口已过）
    await expect(page.getByText("部分回答")).toBeVisible();
    // 重连成功后续流渲染（EOF → 50ms → runReconnect 立即 → 续帧交付）
    await expect(page.getByText("续流后的完整回答")).toBeVisible({ timeout: 10_000 });
    // 携带最后消费的 seq 锚点（3）
    expect(reconnectUrl).toContain("lastEventId=3");
    // 操作栏浮现（续流 end 落位）
    await expect(page.getByRole("button", { name: "复制回答" })).toBeVisible();
  });

  test("REPLAY_FAILED：重连失败引导重新提问", async ({ page }) => {
    await mockChatStream(
      page,
      frame("metadata", { runId: "9001", sessionId: "77", model: "qwen3.8-max" }, 1) +
        frame("thinking", { delta: "思考" }, 2),
    );
    await page.route("**/api/v1/student/chat/9001/reconnect*", async (route) => {
      await route.fulfill({
        status: 200,
        headers: { "Content-Type": "text/event-stream;charset=UTF-8" },
        body: frame("error", { message: "会话历史不可用，请重新提问", code: "REPLAY_FAILED" }),
      });
    });
    await login(page, "/");
    await page.goto("/chat");
    await page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行").fill("提问");
    await page.getByRole("button", { name: "发送" }).click();
    // 渲染屏障：第一段流消费完成（推理卡默认收起，2026-08-27——预览行承载思考末行）。
    // EOF 未终态 → 50ms 后 runReconnect → 重连收到 error(REPLAY_FAILED) 帧 → replay_failed 分级横幅
    await expect(page.getByTestId("reasoning-preview")).toBeVisible();
    await expect(page.getByText("会话历史不可用，请重新提问")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByRole("button", { name: "重新提问" })).toBeVisible();
  });

  test("CANCELLED 终态：已停止生成后缀且无反馈入口", async ({ page }) => {
    await mockChatStream(
      page,
      frame("metadata", { runId: "9001", sessionId: "77", model: "qwen3.8-max" }, 1) +
        frame("delta", { text: "半截回答" }, 2) +
        frame("end", { runId: "9001", status: "CANCELLED" }, 3),
    );
    await login(page, "/");
    await page.goto("/chat");
    await page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行").fill("提问");
    await page.getByRole("button", { name: "发送" }).click();
    await expect(page.getByText(/半截回答.*已停止生成/)).toBeVisible();
    // CANCELLED 无 messageId：无反馈按钮（仅复制）
    await expect(page.getByRole("button", { name: "有用" })).toBeHidden();
    await expect(page.getByRole("button", { name: "复制回答" })).toBeVisible();
  });
});
