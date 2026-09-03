import { test, expect, type Page } from "@playwright/test";
import { mockApi, login, mockChatStream, frame } from "./helpers/sse-route";

/**
 * 多会话并行 E2E（spec §4 M8 / 决策 D7：复现路径 = 生成中切走再切回；
 * T6 主修复 = ring lastEventId<=0 全量回放 + active-run 端点族 + resume 占位）
 *
 * 覆盖三条复现场景（route-mock 全量拦截，不依赖真实后端）：
 * 1. 会话 A 长生成（>256 事件回放 mock）→ 新建对话 B 发送 → 切回 A：
 *    已生成内容经全量回放完整可见（首个/末个 delta 片段）+ 锚点续流推终态
 *    （操作栏浮现）——M8 主复现，验证切走再切回不再全空
 * 2. A 生成中 page.reload()：历史空窗口先渲染「正在继续生成…」占位（M6.4），
 *    回放帧到达后进行中内容可见——刷新场景的占位/恢复双分支
 * 3. 两会话同时流式互切（H3 锚点）：key={sessionId} 重挂载隔离——DOM 仅一个
 *    工作区、各会话工作区只含本会话消息，对端回答片段不串入
 *
 * mock 手法（沿用 helpers/sse-route 既有模式）：resume 全量回放 = GET reconnect
 * 无查询参数交付「回放段」（无终态，流尾 EOF → 前端携锚点续连）；?lastEventId
 * 续连段经 delayMs 挂起后交付终态——挂起窗口即「生成进行中」，切走/刷新/互切
 * 均发生在此窗口内。断言全部 web-first（expect 轮询 + 自动等待，禁固定 sleep）。
 */

/** 挂起等待（仅用于 route handler 内模拟服务端延迟，非测试断言等待） */
const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/** 会话列表条目形状（侧栏 /chat/{id} 跳转锚点） */
interface SessionSeed {
  id: string;
  title: string;
}

/** 历史用户提问行（StudentMessage 形状；ACTIVE run 期间仅 USER 行落库，增量行终态才补全） */
function userRow(id: string, content: string, runId: string): Record<string, unknown> {
  return {
    id,
    role: "USER",
    content,
    messageType: null,
    thinkingStage: null,
    intentType: null,
    runId,
    seq: 1,
    createdAt: "2026-09-01T10:00:00",
    sources: [],
    attachments: [],
  };
}

/** 侧栏会话列表 mock（后注册优先于 mockApi 通用 mock；分页单页全量返回） */
async function mockSessionList(page: Page, sessions: SessionSeed[]) {
  await page.route("**/api/v1/student/sessions?**", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        code: 0,
        message: "success",
        data: {
          records: sessions.map((s) => ({
            id: s.id,
            title: s.title,
            status: "ACTIVE",
            lastMessageAt: null,
            createdAt: "2026-08-24T09:20:00",
          })),
          total: String(sessions.length),
          page: 1,
          size: 20,
        },
      }),
    });
  });
}

/**
 * 会话运行时 mock：历史消息分页（升序一页）+ active-run 锚点
 *
 * @param sessionId  会话 id（URL 路径参数）
 * @param history    历史行（StudentMessage 形状；空数组 = 历史空窗口场景）
 * @param activeRunId 活跃 run id（M8：切回续流锚点；null = 无活跃 run）
 */
async function mockSessionRuntime(
  page: Page,
  sessionId: string,
  history: Array<Record<string, unknown>>,
  activeRunId: string | null,
) {
  await page.route(`**/api/v1/student/sessions/${sessionId}/messages?**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        code: 0,
        message: "success",
        data: { records: history, total: String(history.length), page: 1, size: 200 },
      }),
    });
  });
  await page.route(`**/api/v1/student/chat/session/${sessionId}/active-run`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ code: 0, message: "success", data: { runId: activeRunId } }),
    });
  });
}

/**
 * 长生成 SSE mock（run 维度，覆盖 GET reconnect 两形态）：
 * - 无查询参数（resume 全量回放）：延迟 replayDelayMs 后交付回放段（无终态）——
 *   流尾 EOF 触发前端锚点续连，进入挂起窗口
 * - ?lastEventId=（锚点续连）：延迟 resumeDelayMs 后交付终态段——延迟即「生成
 *   进行中」的挂起窗口，切走/刷新/互切在此窗口内完成后再「解除挂起推 end」
 *
 * @returns anchorUrls 锚点续连请求 URL 收集器（断言 lastEventId 携带值）
 */
async function mockHangingRunStream(
  page: Page,
  runId: string,
  replayBody: string,
  opts: { replayDelayMs?: number; resumeDelayMs: number; resumeBody: string },
): Promise<{ anchorUrls: string[] }> {
  const anchorUrls: string[] = [];
  await page.route(`**/api/v1/student/chat/${runId}/reconnect*`, async (route) => {
    const url = route.request().url();
    const isAnchorContinue = new URL(url).searchParams.has("lastEventId");
    if (!isAnchorContinue) {
      if (opts.replayDelayMs) await sleep(opts.replayDelayMs);
      await route.fulfill({
        status: 200,
        headers: { "Content-Type": "text/event-stream;charset=UTF-8" },
        body: replayBody,
      });
      return;
    }
    anchorUrls.push(url);
    await sleep(opts.resumeDelayMs);
    await route.fulfill({
      status: 200,
      headers: { "Content-Type": "text/event-stream;charset=UTF-8" },
      body: opts.resumeBody,
    });
  });
  return { anchorUrls };
}

/**
 * 长生成回放帧（metadata + N 个 delta，无终态）
 *
 * 事件量 >256（ring 旧容量临界，M8 复现的事件量条件）；首/末 delta 用可定位的
 * 独立文案，断言「回放恢复覆盖完整保留窗口」时分别锚定窗口两端。
 */
function longReplayFrames(runId: string, sessionId: string, deltaCount: number): string {
  const parts: string[] = [frame("metadata", { runId, sessionId, model: "qwen3.8-max" }, 1)];
  for (let i = 1; i <= deltaCount; i += 1) {
    const text =
      i === 1 ? "长生成首段。" : i === deltaCount ? `长流第${deltaCount}段收尾。` : `段${i}`;
    parts.push(frame("delta", { text }, i + 1));
  }
  return parts.join("");
}

/** 登录并直达指定会话页（侧栏就绪为进入屏障） */
async function goSession(page: Page, sessionId: string) {
  await login(page, "/");
  await page.goto(`/chat/${sessionId}`);
  await expect(page.getByTestId("chat-sidebar")).toBeVisible();
}

test.describe("多会话并行（M8/D7）", () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page);
  });

  test("会话 A 长生成（300 事件）→ 新建对话 B 发送 → 切回 A：全量回放恢复 + 续流推终态", async ({
    page,
  }) => {
    // 数据面：侧栏仅会话 A；A 的历史仅 USER 提问行（ACTIVE run 增量行终态才落库），
    // active-run 命中 run-9001（切走期间服务端继续执行）
    await mockSessionList(page, [{ id: "100", title: "会话 A" }]);
    await mockSessionRuntime(page, "100", [userRow("hm-u1", "长生成的问题", "9001")], "9001");
    // 流面：回放段 300 delta（seq 2..301）无终态；锚点续连挂起 1.5s 后推终态
    const { anchorUrls } = await mockHangingRunStream(
      page,
      "9001",
      longReplayFrames("9001", "100", 300),
      {
        replayDelayMs: 300,
        resumeDelayMs: 1500,
        resumeBody:
          frame("delta", { text: "续流终局回答。" }, 302) +
          frame("end", { runId: "9001", status: "COMPLETED", messageId: "5001" }, 303),
      },
    );
    // B（新对话）流：一次性交付含终态（本用例只作切换插曲，终态互不干扰）
    await mockChatStream(
      page,
      frame("metadata", { runId: "9002", sessionId: "300", model: "qwen3.8-max" }, 1) +
        frame("delta", { text: "B 会话独立回答" }, 2) +
        frame("end", { runId: "9002", status: "COMPLETED", messageId: "5002" }, 3),
      { delayMs: 300 },
    );

    // ── 进入 A：长生成进行中，部分内容可见 ──
    await goSession(page, "100");
    await expect(page.getByTestId("assistant-message")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("message-flow")).toContainText("长生成首段。");
    await expect(page.getByTestId("message-flow")).toContainText("长流第300段收尾。");

    // ── 切到 B：侧栏新建对话（/chat 非同路由 → 导航进入）→ 发送 ──
    await page.getByRole("button", { name: "新建对话" }).click();
    await expect(page).toHaveURL(/\/chat$/);
    await page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行").fill("B 的提问");
    await page.getByRole("button", { name: "发送" }).click();
    await expect(page.getByTestId("message-flow")).toContainText("B 会话独立回答", {
      timeout: 10_000,
    });

    // ── 切回 A（侧栏路由导航）：已生成内容经全量回放完整可见（M8 主断言）──
    await page.getByRole("link", { name: "会话 A" }).click();
    await expect(page).toHaveURL(/\/chat\/100$/);
    await expect(page.getByTestId("assistant-message")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("message-flow")).toContainText("长生成的问题");
    await expect(page.getByTestId("message-flow")).toContainText("长生成首段。");
    await expect(page.getByTestId("message-flow")).toContainText("长流第300段收尾。");

    // ── 续流继续：解除挂起推 end → 终态操作栏浮现（web-first 轮询穿透挂起窗口）──
    await expect(page.getByRole("button", { name: "复制回答" })).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("message-flow")).toContainText("续流终局回答。");
    // 锚点续连携带回放段末位 seq（长事件量下锚点不漂移：300 delta + metadata = 301）
    expect(anchorUrls.length).toBeGreaterThan(0);
    expect(anchorUrls[anchorUrls.length - 1]).toContain("lastEventId=301");
  });

  test("A 生成中刷新：历史空窗口先占位提示，回放帧到达后进行中内容可见（M6.4）", async ({
    page,
  }) => {
    // 数据面：A 历史为空（ACTIVE run 无落库行）+ active-run 命中 → 占位条件成立
    await mockSessionList(page, [{ id: "100", title: "会话 A" }]);
    await mockSessionRuntime(page, "100", [], "9001");
    // 流面：回放段延迟 800ms 交付（占位可见窗口）；锚点续连长挂起保持「生成中」
    await mockHangingRunStream(
      page,
      "9001",
      frame("metadata", { runId: "9001", sessionId: "100", model: "qwen3.8-max" }, 1) +
        frame("delta", { text: "刷新回放的进行中片段" }, 2),
      {
        replayDelayMs: 800,
        resumeDelayMs: 4000,
        resumeBody: frame("end", { runId: "9001", status: "COMPLETED" }, 3),
      },
    );

    // 首次进入：占位（回放未送达的空窗口）→ 回放帧到达转内容
    await goSession(page, "100");
    await expect(page.getByTestId("resume-placeholder")).toBeVisible();
    await expect(page.getByTestId("message-flow")).toContainText("刷新回放的进行中片段", {
      timeout: 10_000,
    });

    // 生成中刷新：占位分支与回放恢复分支再次成立（刷新不丢进行中视图）
    await page.reload();
    await expect(page.getByTestId("resume-placeholder")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("message-flow")).toContainText("刷新回放的进行中片段", {
      timeout: 10_000,
    });
  });

  test("两会话同时流式互切：DOM 仅一个工作区，B 回答不串入 A 工作区（H3 锚点）", async ({
    page,
  }) => {
    // 数据面：A/B 两会话各有活跃 run（同时流式），历史各含本会话 USER 提问行
    await mockSessionList(page, [
      { id: "100", title: "会话 A" },
      { id: "200", title: "会话 B" },
    ]);
    await mockSessionRuntime(page, "100", [userRow("hm-a", "A 的提问", "9001")], "9001");
    await mockSessionRuntime(page, "200", [userRow("hm-b", "B 的提问", "9002")], "9002");
    // 流面：两会话各自挂起流（回放段各带专属片段，锚点续连长挂起保持双方生成中）
    await mockHangingRunStream(
      page,
      "9001",
      frame("metadata", { runId: "9001", sessionId: "100", model: "qwen3.8-max" }, 1) +
        frame("delta", { text: "会话A专属回答片段" }, 2),
      {
        replayDelayMs: 300,
        resumeDelayMs: 4000,
        resumeBody: frame("end", { runId: "9001", status: "COMPLETED" }, 3),
      },
    );
    await mockHangingRunStream(
      page,
      "9002",
      frame("metadata", { runId: "9002", sessionId: "200", model: "qwen3.8-max" }, 1) +
        frame("delta", { text: "会话B专属回答片段" }, 2),
      {
        replayDelayMs: 300,
        resumeDelayMs: 4000,
        resumeBody: frame("end", { runId: "9002", status: "COMPLETED" }, 3),
      },
    );

    // ── 进入 A：A 片段可见 ──
    await goSession(page, "100");
    await expect(page.getByTestId("message-flow")).toContainText("会话A专属回答片段", {
      timeout: 10_000,
    });

    // ── 切到 B：B 片段可见，A 片段不串入（key 重挂载：同一时刻 DOM 仅一个工作区）──
    await page.getByRole("link", { name: "会话 B" }).click();
    await expect(page).toHaveURL(/\/chat\/200$/);
    await expect(page.getByTestId("message-flow")).toContainText("会话B专属回答片段", {
      timeout: 10_000,
    });
    await expect(page.getByTestId("chat-workspace")).toHaveCount(1);
    await expect(page.getByTestId("message-flow")).not.toContainText("会话A专属回答片段");

    // ── 切回 A：A 片段可见，B 片段不串入（互切双向复核）──
    await page.getByRole("link", { name: "会话 A" }).click();
    await expect(page).toHaveURL(/\/chat\/100$/);
    await expect(page.getByTestId("message-flow")).toContainText("会话A专属回答片段", {
      timeout: 10_000,
    });
    await expect(page.getByTestId("chat-workspace")).toHaveCount(1);
    await expect(page.getByTestId("message-flow")).not.toContainText("会话B专属回答片段");
  });
});
