import { createServer, type Server } from "node:http";
import { test, expect } from "@playwright/test";
import { mockApi, login, mockChatStream, frame } from "./helpers/sse-route";

/**
 * 链式时间轴 E2E（2026-08-28 时间线改版核心交付，Task 15a；2026-08-30 对齐设计稿；
 * 2026-09-03 渲染序拍板修订）
 *
 * - 渲染序：严格按事件到达序（=服务端触发序，2026-09-03 用户拍板：检索卡不可能排
 *   首位——理解思考恒先于检索；M3「sources 强制前置」前端兜底已移除）→ thinking →
 *   sources → tool → 答案正文按竖线串联，节点先于正文（stage/query_plan 帧后端
 *   照发、前端忽略——「正在生成回答」「未识别意图」不再展示）
 * - 折叠：思考步骤默认收起（末行可见），点击头部展开全量思考行
 * - 抽屉：检索步骤点击打开召回抽屉（片段卡承载 docTitle；正文按 id 懒加载回查 PG）；
 *   工具步骤点击打开工具结果抽屉（结构化卡片 + 原始 JSON 折叠）
 * - 分帧流式：逐帧延迟送达断言正文逐块增量渲染（防「一次性吐出」回归）
 *
 * 原则：route-mock 全量、web-first 断言、禁固定 sleep（帧同批送达以终态收敛断言；
 * 分帧用例以流内延迟驱动、断言仍 web-first）。
 */

test.describe("链式时间轴", () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page);
  });

  /** 完整时间轴事件流（到达序：thinking → sources → tool → delta；2026-08-30 无 stage/query_plan） */
  const FULL_TIMELINE =
    frame("metadata", { runId: "9001", sessionId: "77", model: "qwen3.8-max" }, 1) +
    frame("thinking", { delta: "第一行思考：分析提问意图", stage: "understanding" }, 2) +
    frame("thinking", { delta: "\n第二行思考：确定检索关键词", stage: "understanding" }, 3) +
    frame("thinking_end", { stage: "understanding" }, 4) +
    frame(
      "sources",
      {
        sources: [
          {
            chunkId: "101",
            docTitle: "课程讲义第3章",
            headingPath: "第3章 > 3.2",
            score: 0.92,
          },
        ],
      },
      5,
    ) +
    frame(
      "tool_call",
      { toolCallId: "t-1", toolName: "searchKnowledge", input: { query: "倒排索引" } },
      6,
    ) +
    frame("tool_result", { toolCallId: "t-1", status: "success", output: "命中课程讲义第3章" }, 7) +
    frame("delta", { text: "答案正文开始。" }, 8) +
    frame("end", { runId: "9001", status: "COMPLETED", messageId: "5001" }, 9);

  test("渲染序：严格按事件到达序（理解思考 → sources → tool → 正文；M3 前置兜底已移除，检索卡不排首位；stage/query_plan 帧被忽略）", async ({
    page,
  }) => {
    // 事件流中混入 stage/query_plan 帧（后端照发），断言前端忽略不渲染
    const timelineWithIgnored =
      frame("metadata", { runId: "9001", sessionId: "77", model: "qwen3.8-max" }, 1) +
      frame("stage", { stage: "understanding", label: "正在理解你的问题" }, 2) +
      frame("thinking", { delta: "第一行思考", stage: "understanding" }, 3) +
      frame(
        "query_plan",
        {
          intent: "knowledge_question",
          rewritten: ["倒排索引 原理"],
          filters: { courseNames: [] },
        },
        4,
      ) +
      frame(
        "sources",
        { sources: [{ chunkId: "101", docTitle: "课程讲义第3章", headingPath: "", score: 0.92 }] },
        5,
      ) +
      frame("tool_call", { toolCallId: "t-1", toolName: "searchKnowledge", input: {} }, 6) +
      frame("tool_result", { toolCallId: "t-1", status: "success", output: "命中" }, 7) +
      frame("delta", { text: "答案正文。" }, 8) +
      frame("end", { runId: "9001", status: "COMPLETED", messageId: "5001" }, 9);
    await mockChatStream(page, timelineWithIgnored);
    await login(page, "/");
    await page.goto("/chat");
    await page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行").fill("倒排索引");
    await page.getByRole("button", { name: "发送" }).click();

    // 时间轴就位 + 各节点可见
    await expect(page.getByTestId("chain-timeline")).toBeVisible();
    const thinking = page.getByTestId("thinking-step");
    const sources = page.getByTestId("sources-step");
    const tool = page.getByTestId("tool-step");
    await expect(thinking).toBeVisible();
    await expect(sources).toBeVisible();
    await expect(tool).toBeVisible();
    // 2026-08-30 对齐设计稿：stage/query_plan 帧被忽略，不渲染「正在生成回答」/查询计划步骤
    await expect(page.getByText("正在理解你的问题")).toHaveCount(0);
    await expect(page.getByText("正在生成回答")).toHaveCount(0);
    await expect(page.getByTestId("query-plan-step")).toHaveCount(0);

    // 2026-09-03 渲染序拍板：严格按到达序逐对校验——thinking < sources < tool < 正文
    //（检索卡位于理解思考之后，不可能出现在首位）
    const boxes = await Promise.all([
      thinking.boundingBox(),
      sources.boundingBox(),
      tool.boundingBox(),
      page.getByTestId("markdown-view").boundingBox(),
    ]);
    for (let i = 0; i < boxes.length - 1; i++) {
      expect(boxes[i] && boxes[i + 1] && boxes[i]!.y <= boxes[i + 1]!.y).toBeTruthy();
    }
    // 工具步骤完成态：人话工具名 + 结果摘要
    await expect(tool).toContainText("检索课程知识库");
    await expect(tool).toContainText("命中");
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

  test("抽屉：检索步骤点击打开召回抽屉，展开卡片按 id 懒加载片段全文", async ({ page }) => {
    await mockChatStream(page, FULL_TIMELINE);
    await login(page, "/");
    await page.goto("/chat");
    await page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行").fill("倒排索引");
    await page.getByRole("button", { name: "发送" }).click();
    const sources = page.getByTestId("sources-step");
    await expect(sources).toContainText("已检索 1 篇相关资料");

    // 点击检索步骤 → 召回抽屉滑入；片段卡默认收起（无正文——内容懒加载）
    await sources.click();
    const drawer = page.getByTestId("retrieval-drawer");
    await expect(drawer).toBeVisible();
    const card = drawer.getByTestId("retrieval-source-item");
    await expect(card).toContainText("课程讲义第3章");
    await expect(drawer.getByTestId("retrieval-source-text")).toHaveCount(0);
    // 展开卡片 → 按 chunkId 回查 PG 拉全文（web-first 等待）
    await card.click();
    await expect(drawer.getByTestId("retrieval-source-text")).toContainText(
      "倒排索引是信息检索的核心数据结构",
    );
  });

  test("抽屉：工具步骤点击打开工具结果抽屉（结构化卡片 + 原始 JSON 折叠）", async ({ page }) => {
    const timeline =
      frame("metadata", { runId: "9001", sessionId: "77", model: "qwen3.8-max" }, 1) +
      frame(
        "tool_call",
        { toolCallId: "t-1", toolName: "listCourses", input: { keyword: "Java" } },
        2,
      ) +
      frame(
        "tool_result",
        {
          toolCallId: "t-1",
          status: "success",
          // 后端真实契约：output 为 JSON 字符串（SseEventTransformer/ChatRequestWorker
          // 直取 responseData()，且可能被 4000 截断——前端容错 parse 后结构化成卡）
          output: JSON.stringify({
            page: 1,
            total: 1,
            courses: [{ courseId: "2", title: "Java 从入门到进阶", price: "¥0", category: "编程" }],
          }),
        },
        3,
      ) +
      frame("delta", { text: "为你找到 Java 课程。" }, 4) +
      frame("end", { runId: "9001", status: "COMPLETED", messageId: "5001" }, 5);
    await mockChatStream(page, timeline);
    await login(page, "/");
    await page.goto("/chat");
    await page
      .getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行")
      .fill("有哪些 Java 课程");
    await page.getByRole("button", { name: "发送" }).click();

    // 工具步骤完成态点击 → 工具结果抽屉（2026-08-30 工具结果侧栏展示）
    const tool = page.getByTestId("tool-step");
    await expect(tool).toContainText("查询课程列表");
    await tool.click();
    const drawer = page.getByTestId("tool-drawer");
    await expect(drawer).toBeVisible();
    await expect(drawer.getByTestId("tool-drawer-sub")).toContainText("查询课程列表");
    // 结构化卡片：课程标题 + 价格 + 分类
    await expect(drawer.getByTestId("tool-result-item")).toContainText("Java 从入门到进阶");
    await expect(drawer.getByTestId("tool-result-item")).toContainText(/价格: ¥0/);
    // 原始 JSON 折叠：默认收起 → 点击展开完整输出
    await expect(drawer.getByTestId("tool-drawer-raw")).toHaveCount(0);
    await drawer.getByTestId("tool-drawer-raw-toggle").click();
    await expect(drawer.getByTestId("tool-drawer-raw")).toContainText('"courseId": "2"');
  });

  test("分帧流式：正文逐帧增量渲染，不等待全部内容返回（防「一次性吐出」回归）", async ({
    page,
  }) => {
    // Playwright route.fulfill 不支持流式 body（1.62 类型仅 string|Buffer）——
    // 用真实 node http 服务器按帧延迟推送 SSE，验证前端逐帧增量渲染
    const frames = [
      frame("metadata", { runId: "9001", sessionId: "77", model: "qwen3.8-max" }, 1),
      frame("thinking", { delta: "分析中", stage: "understanding" }, 2),
      frame("thinking_end", { stage: "understanding" }, 3),
      frame("delta", { text: "第一段回答。" }, 4),
      frame("delta", { text: "第二段回答。" }, 5),
      frame("end", { runId: "9001", status: "COMPLETED", messageId: "5001" }, 6),
    ];
    let frameIndex = 0;
    const server: Server = createServer((_req, res) => {
      res.writeHead(200, { "Content-Type": "text/event-stream;charset=UTF-8" });
      const timer = setInterval(() => {
        if (frameIndex < frames.length) {
          res.write(frames[frameIndex++]);
        } else {
          clearInterval(timer);
          res.end();
        }
      }, 400);
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    const address = server.address();
    const port = typeof address === "object" && address !== null ? address.port : 0;
    // 聊天请求转发到分帧服务器（method/headers/body 原样透传）
    await page.route("**/api/v1/student/chat", (route) =>
      route.continue({ url: `http://127.0.0.1:${port}` }),
    );
    try {
      await login(page, "/");
      await page.goto("/chat");
      await page
        .getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行")
        .fill("什么是倒排索引");
      await page.getByRole("button", { name: "发送" }).click();

      const answer = page.getByTestId("markdown-view");
      // 第一帧正文到达即渲染（流式中）
      await expect(answer).toContainText("第一段回答。");
      // 关键中间态断言：第二段尚未到达（一次性吐出回归会被此断言抓住）
      await expect(answer).not.toContainText("第二段回答。");
      // 终态收敛：全部正文最终渲染完成
      await expect(answer).toContainText("第二段回答。");
      await expect(page.getByTestId("typing-cursor")).toHaveCount(0);
    } finally {
      server.close();
    }
  });
});
