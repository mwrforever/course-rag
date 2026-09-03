import { test, expect } from "@playwright/test";
import { mockApi, login, mockChatStream, frame } from "./helpers/sse-route";

/**
 * SSE 生命周期 E2E（整合 spec §3.2：cancel / 409 / reconnect / REPLAY_FAILED / M7 错误卡）
 *
 * - cancel：发送后按钮 morph「停止生成」→ 点击 → POST {runId}/cancel 请求到达
 * - 409：二次发送冲突 → toast「当前会话正在回答中」
 * - reconnect：EOF 未终态触发断流路径（Task11 修复：EOF 即断流，无需等 30s）→
 *   GET reconnect?lastEventId= 携带锚点 → 续流渲染
 * - REPLAY_FAILED：重连返回 error 帧 → 「重新提问」引导
 * - M7 错误卡「重新生成」：error 终态 → 横幅入口 → POST replay REGENERATE → 新回答流式
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
    // 渲染屏障：第一段流消费完成（2026-08-28 时间线改版——思考步骤收起态在位，
    // thinking-body 承载思考行）。
    // EOF 未终态 → 50ms 后 runReconnect → 重连收到 error(REPLAY_FAILED) 帧 → replay_failed 分级横幅
    await expect(page.getByTestId("thinking-body")).toBeVisible();
    await expect(page.getByText("会话历史不可用，请重新提问")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByRole("button", { name: "重新提问" })).toBeVisible();
  });

  test("CANCELLED 终态（2026-09-03 停止态拍板）：正文无后缀、底部小字提醒、反馈入口保留", async ({
    page,
  }) => {
    await mockChatStream(
      page,
      frame("metadata", { runId: "9001", sessionId: "77", model: "qwen3.8-max" }, 1) +
        frame("delta", { text: "半截回答" }, 2) +
        // 后端取消落库回填半截正文行 id → END CANCELLED 携带（停止后反馈入口依据）
        frame("end", { runId: "9001", status: "CANCELLED", messageId: "5009" }, 3),
    );
    await login(page, "/");
    await page.goto("/chat");
    await page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行").fill("提问");
    await page.getByRole("button", { name: "发送" }).click();
    // 正文原样（不再拼「已停止生成」后缀）
    await expect(page.getByTestId("markdown-view")).toHaveText("半截回答");
    // 整块内容最底部小字提醒（操作栏之后，非标签/徽章样式）
    await expect(page.getByTestId("stopped-hint")).toHaveText("这条消息已停止");
    const hintBox = await page.getByTestId("stopped-hint").boundingBox();
    const barBox = await page.getByTestId("feedback-bar").boundingBox();
    expect(hintBox && barBox && barBox.y < hintBox.y).toBeTruthy();
    // 反馈入口保留（CANCELLED 携 messageId）：有用/无用/复制/重新生成均在场
    await expect(page.getByRole("button", { name: "复制回答" })).toBeVisible();
    await expect(page.getByRole("button", { name: "有用" })).toBeVisible();
    await expect(page.getByRole("button", { name: "无用" })).toBeVisible();
    await expect(page.getByRole("button", { name: "重新生成" })).toBeVisible();
    // 旧徽标形态下线
    await expect(page.getByTestId("incomplete-badge-cancelled")).toHaveCount(0);
  });

  test("CANCELLED 无 messageId（降级窗口）：仅复制无反馈，小字提醒仍在", async ({ page }) => {
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
    await expect(page.getByTestId("stopped-hint")).toBeVisible();
    await expect(page.getByRole("button", { name: "有用" })).toBeHidden();
    await expect(page.getByRole("button", { name: "复制回答" })).toBeVisible();
  });

  test("错误卡「重新生成」入口：run ERROR 后点击 → POST replay REGENERATE → 新回答流式（M7+M5）", async ({
    page,
  }) => {
    // route-mock：首轮流推 error 终态（服务端判死重试耗尽/有产出失败的保留现场形态）→
    // 错误横幅出现 → 点击 error-regenerate → 断言 replay 请求体 {mode, targetRunId} →
    // replay 流接管渲染新回答（web-first 断言，无固定 sleep）
    await mockChatStream(
      page,
      frame("metadata", { runId: "9001", sessionId: "77", model: "qwen3.8-max" }, 1) +
        frame("delta", { text: "半截回答" }, 2) +
        frame("error", { runId: "9001", message: "网络连接中断，请重试" }, 3),
      { delayMs: 300 },
    );
    let replayBody: string | null = null;
    await page.route("**/api/v1/student/chat/session/77/replay", async (route) => {
      replayBody = route.request().postData() ?? null;
      await route.fulfill({
        status: 200,
        headers: { "Content-Type": "text/event-stream;charset=UTF-8" },
        body:
          frame("metadata", { runId: "9002", sessionId: "77", model: "qwen3.8-max" }, 1) +
          frame("delta", { text: "重新生成的完整回答" }, 2) +
          frame("end", { runId: "9002", status: "COMPLETED", messageId: "5002" }, 3),
      });
    });

    await login(page, "/");
    await page.goto("/chat");
    await page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行").fill("提问");
    await page.getByRole("button", { name: "发送" }).click();
    // 错误横幅出现（error 帧落位 state.error；runId 保留 → 重新生成入口渲染）
    await expect(page.getByText("网络连接中断，请重试")).toBeVisible({ timeout: 8_000 });
    const regenerate = page.getByTestId("error-regenerate");
    await expect(regenerate).toBeVisible();
    await regenerate.click();
    // replay 请求体断言：REGENERATE 携带目标 runId（poll 通过后 replayBody 必非空，?? "" 仅过类型）
    await expect.poll(() => replayBody).toBeTruthy();
    const parsed = JSON.parse(replayBody ?? "") as { mode: string; targetRunId: string };
    expect(parsed.mode).toBe("REGENERATE");
    expect(parsed.targetRunId).toBe("9001");
    // 新流渲染：replay 200 → replay_rollback 移除旧回答 → 新 run 流式接续
    await expect(page.getByText("重新生成的完整回答")).toBeVisible({ timeout: 8_000 });
    await expect(page.getByRole("button", { name: "复制回答" })).toBeVisible();
  });
});
