import { test, expect } from "@playwright/test";
import { mockApi, login, mockChatStream, frame } from "./helpers/sse-route";

/**
 * 链式时间轴 E2E（2026-08-28 时间线改版核心交付，Task 15a）
 *
 * - 渲染序：stage → thinking → query_plan → sources → tool → 答案正文按 SSE 到达序
 *   挂链（竖线串联，节点先于正文）
 * - 折叠：思考步骤默认收起（末行可见），头部点击展开全量思考行
 * - 抽屉：检索步骤点击打开召回抽屉（片段卡承载 docTitle/正文）
 *
 * 原则：route-mock 全量、web-first 断言、禁固定 sleep（帧同批送达以终态收敛断言）。
 */

test.describe("链式时间轴", () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page);
  });

  /** 完整五类节点事件流（到达序：stage → thinking → query_plan → sources → tool → delta） */
  const FULL_TIMELINE =
    frame("metadata", { runId: "9001", sessionId: "77", model: "qwen3.8-max" }, 1) +
    frame("stage", { stage: "understanding", label: "正在理解你的问题" }, 2) +
    frame("thinking", { delta: "第一行思考：分析提问意图", stage: "understanding" }, 3) +
    frame("thinking", { delta: "\n第二行思考：确定检索关键词", stage: "understanding" }, 4) +
    frame("thinking_end", { stage: "understanding" }, 5) +
    frame(
      "query_plan",
      {
        intent: "knowledge_question",
        rewritten: ["倒排索引 原理"],
        filters: { courseNames: ["数据结构与算法"] },
      },
      6,
    ) +
    frame("stage", { stage: "retrieving", label: "知识库查询中" }, 7) +
    frame(
      "sources",
      {
        sources: [
          {
            chunkId: "101",
            docTitle: "课程讲义第3章",
            headingPath: "第3章 > 3.2",
            score: 0.92,
            content: "倒排索引是信息检索的核心数据结构……",
          },
        ],
      },
      8,
    ) +
    frame(
      "tool_call",
      { toolCallId: "t-1", toolName: "searchKnowledge", input: { query: "倒排索引" } },
      9,
    ) +
    frame(
      "tool_result",
      { toolCallId: "t-1", status: "success", output: "命中课程讲义第3章" },
      10,
    ) +
    frame("stage", { stage: "generating", label: "正在生成回答" }, 11) +
    frame("delta", { text: "答案正文开始。" }, 12) +
    frame("end", { runId: "9001", status: "COMPLETED", messageId: "5001" }, 13);

  test("渲染序：五类节点按到达序挂链，均位于答案正文之前", async ({ page }) => {
    await mockChatStream(page, FULL_TIMELINE);
    await login(page, "/");
    await page.goto("/chat");
    await page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行").fill("倒排索引");
    await page.getByRole("button", { name: "发送" }).click();

    // 时间轴就位 + 五类节点各自可见
    await expect(page.getByTestId("chain-timeline")).toBeVisible();
    const stage = page.getByTestId("op-step-text").filter({ hasText: "正在理解你的问题" });
    const thinking = page.getByTestId("thinking-step");
    const plan = page.getByTestId("query-plan-step");
    const sources = page.getByTestId("sources-step");
    const tool = page.getByTestId("tool-step");
    await expect(stage).toBeVisible();
    await expect(thinking).toBeVisible();
    await expect(plan).toBeVisible();
    await expect(sources).toBeVisible();
    await expect(tool).toBeVisible();

    // 到达序逐对校验：stage < thinking < query_plan < sources < tool < 正文
    const boxes = await Promise.all([
      stage.boundingBox(),
      thinking.boundingBox(),
      plan.boundingBox(),
      sources.boundingBox(),
      tool.boundingBox(),
      page.getByTestId("markdown-view").boundingBox(),
    ]);
    for (let i = 0; i < boxes.length - 1; i++) {
      expect(boxes[i] && boxes[i + 1] && boxes[i]!.y <= boxes[i + 1]!.y).toBeTruthy();
    }
    // 工具步骤完成态：人话工具名 + 结果摘要
    await expect(tool).toContainText("检索课程知识库");
    await expect(tool).toContainText("命中课程讲义第3章");
  });

  test("折叠：思考默认收起，点击头部展开全量思考行，再点收起", async ({ page }) => {
    await mockChatStream(page, FULL_TIMELINE);
    await login(page, "/");
    await page.goto("/chat");
    await page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行").fill("倒排索引");
    await page.getByRole("button", { name: "发送" }).click();
    await expect(page.getByTestId("thinking-status")).toHaveText("思考已完成");

    // 默认收起：aria-expanded=false，思考体 DOM 含全部行（26px 窗口锚定底部露末行）
    const toggle = page.getByTestId("thinking-toggle");
    await expect(toggle).toHaveAttribute("aria-expanded", "false");
    // 展开：头部点击 → aria-expanded=true，两行思考内容均在文档流内
    await toggle.click();
    await expect(toggle).toHaveAttribute("aria-expanded", "true");
    await expect(page.getByTestId("thinking-body")).toContainText("第一行思考");
    await expect(page.getByTestId("thinking-body")).toContainText("第二行思考");
    // 再点收起
    await toggle.click();
    await expect(toggle).toHaveAttribute("aria-expanded", "false");
  });

  test("抽屉：检索步骤点击打开召回抽屉，展示片段卡正文", async ({ page }) => {
    await mockChatStream(page, FULL_TIMELINE);
    await login(page, "/");
    await page.goto("/chat");
    await page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行").fill("倒排索引");
    await page.getByRole("button", { name: "发送" }).click();
    const sources = page.getByTestId("sources-step");
    await expect(sources).toContainText("已检索 1 篇相关资料");

    // 点击检索步骤 → 召回抽屉滑入，片段卡承载标题与正文
    // （抽屉内断言：docTitle 文案与工具步骤摘要同串，须 scope 到抽屉容器）
    await sources.click();
    const drawer = page.getByTestId("retrieval-drawer");
    await expect(drawer).toBeVisible();
    await expect(drawer.getByTestId("retrieval-source-item")).toContainText("课程讲义第3章");
    await expect(drawer).toContainText(/倒排索引是信息检索的核心数据结构/);
  });
});
