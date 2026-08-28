import { test, expect, type Page } from "@playwright/test";
import { mockApi, login } from "./helpers/sse-route";

/**
 * 课程助手侧栏 E2E（会话管理化 2026-08-26：侧栏承载会话增删改查；
 * 2026-08-29 Task 13 弹窗化：改名 → RenameDialog、搜索 → 浮层面板）
 *
 * - 会话历史渲染 + 折叠/展开持久化（原契约保留）
 * - 查：搜索浮层面板（聚焦弹出、keyword 防抖请求、空态、Esc 关闭；主列表恒全量）
 * - 改：重命名弹窗（预填标题 + 空标题校验拦截 + PATCH 后列表显示新标题）
 * - 删：二次确认删除（DELETE 后列表为空态）
 * - 登出：二次确认后回首页
 * 数据变化经测试内 route 覆盖（后注册优先于 mockApi 通用 mock）。
 */

test.describe("课程助手侧栏", () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page);
  });

  /** 登录并进入课程助手（侧栏会话 mock 就绪） */
  async function goChat(page: Page) {
    await login(page, "/");
    await page.goto("/chat");
    await expect(page.getByTestId("chat-sidebar")).toBeVisible();
  }

  test("侧栏渲染会话历史，折叠态刷新后保持", async ({ page }) => {
    await goChat(page);

    const sidebar = page.getByTestId("chat-sidebar");
    await expect(sidebar).toHaveClass(/w-64/);
    // 会话历史条目来自显式 mock 的会话列表（mockApi records=1）
    await expect(page.getByTestId("sidebar-session-item")).toHaveCount(1);

    // 折叠：宽度切换为图标态
    await page.getByRole("button", { name: "收起侧栏" }).click();
    await expect(sidebar).toHaveClass(/w-16/);
    await expect(sidebar).not.toHaveClass(/w-64/);

    // 刷新后仍折叠：折叠偏好经 localStorage 持久化
    await page.reload();
    await expect(page.getByTestId("chat-sidebar")).not.toHaveClass(/w-64/);
    await expect(page.getByTestId("chat-sidebar")).toHaveClass(/w-16/);

    // 再展开恢复常规宽度
    await page.getByRole("button", { name: "展开侧栏" }).click();
    await expect(page.getByTestId("chat-sidebar")).toHaveClass(/w-64/);
  });

  test("查：搜索浮层聚焦弹出，keyword 防抖请求 + 空态，Esc 关闭（Task 13）", async ({ page }) => {
    // 带 keyword 的请求返回空（搜索语义由测试内覆盖；主列表仍回全量）
    await page.route("**/api/v1/student/sessions?**", async (route) => {
      const url = route.request().url();
      if (new URL(url).searchParams.has("keyword")) {
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify({
            code: 0,
            message: "success",
            data: { records: [], total: "0", page: 1, size: 20 },
          }),
        });
      } else {
        await route.fallback();
      }
    });
    await goChat(page);
    await expect(page.getByTestId("sidebar-session-item")).toHaveCount(1);
    // 未聚焦：浮层不渲染；聚焦：浮层弹出（结果列表容器在场）
    await expect(page.getByTestId("session-search-dropdown")).toHaveCount(0);
    await page.getByTestId("sidebar-session-search").focus();
    await expect(page.getByTestId("session-search-dropdown")).toBeVisible();
    // 输入关键词：防抖后空结果 → 浮层空态文案（主列表不受 keyword 影响，仍全量 1 条）
    await page.getByTestId("sidebar-session-search").fill("不存在的");
    await expect(page.getByTestId("session-search-empty")).toHaveText(
      /没有找到「不存在的」相关会话/,
    );
    await expect(page.getByTestId("sidebar-session-item")).toHaveCount(1);
    // Esc：浮层关闭（输入保留）
    await page.keyboard.press("Escape");
    await expect(page.getByTestId("session-search-dropdown")).toHaveCount(0);
    await expect(page.getByTestId("sidebar-session-search")).toHaveValue("不存在的");
  });

  test("改：重命名弹窗预填旧标题 → 空标题校验拦截 → 保存后列表刷新（Task 13）", async ({
    page,
  }) => {
    // 可变标题：PATCH 更新 → 后续 GET 列表回新值（模拟服务端持久化）
    let title = "数据结构与算法咨询";
    await page.route("**/api/v1/student/sessions**", async (route) => {
      const req = route.request();
      const method = req.method();
      const path = new URL(req.url()).pathname;
      if (method === "GET" && path.endsWith("/student/sessions")) {
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify({
            code: 0,
            message: "success",
            data: {
              records: [
                {
                  id: "10",
                  title,
                  status: "ACTIVE",
                  lastMessageAt: null,
                  createdAt: "2026-08-24T09:20:00",
                },
              ],
              total: "1",
              page: 1,
              size: 20,
            },
          }),
        });
      } else if (method === "PATCH" && /\/student\/sessions\/\d+$/.test(path)) {
        title = JSON.parse(req.postData() ?? "{}").title;
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify({ code: 0, message: "success", data: null }),
        });
      } else {
        await route.fallback();
      }
    });
    await goChat(page);
    await expect(page.getByText("数据结构与算法咨询")).toBeVisible();
    // 打开重命名弹窗（预填旧标题）
    await page.getByRole("button", { name: /编辑会话标题/ }).click();
    const dialog = page.getByRole("dialog", { name: "重命名会话" });
    await expect(dialog).toBeVisible();
    await expect(page.getByTestId("rename-input")).toHaveValue("数据结构与算法咨询");
    // 空标题：zod 校验中文错误拦截（不发 PATCH）
    await page.getByTestId("rename-input").fill("   ");
    await page.getByRole("button", { name: /保存/ }).click();
    await expect(page.getByText("标题不能为空")).toBeVisible();
    // 填新标题保存：弹窗关闭 + 列表刷新显示新标题（web-first 轮询）
    await page.getByTestId("rename-input").fill("重命名后的标题");
    await page.getByRole("button", { name: /保存/ }).click();
    await expect(page.getByRole("dialog", { name: "重命名会话" })).toHaveCount(0);
    await expect(page.getByText("重命名后的标题")).toBeVisible();
  });

  test("删：二次确认删除 → 列表更新为空态", async ({ page }) => {
    // 删除后列表为空（模拟级联软删生效）
    let deleted = false;
    await page.route("**/api/v1/student/sessions**", async (route) => {
      const req = route.request();
      const method = req.method();
      const path = new URL(req.url()).pathname;
      if (method === "GET" && path.endsWith("/student/sessions")) {
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify({
            code: 0,
            message: "success",
            data: {
              records: deleted
                ? []
                : [
                    {
                      id: "10",
                      title: "数据结构与算法咨询",
                      status: "ACTIVE",
                      lastMessageAt: null,
                      createdAt: "2026-08-24T09:20:00",
                    },
                  ],
              total: deleted ? "0" : "1",
              page: 1,
              size: 20,
            },
          }),
        });
      } else if (method === "DELETE") {
        deleted = true;
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify({ code: 0, message: "success", data: null }),
        });
      } else {
        await route.fallback();
      }
    });
    await goChat(page);
    await expect(page.getByText("数据结构与算法咨询")).toBeVisible();
    // 第一步：删除按钮 → 确认框（未确认不删）
    await page.getByRole("button", { name: /删除会话/ }).click();
    await expect(page.getByRole("dialog", { name: "删除会话" })).toBeVisible();
    await page.getByRole("button", { name: "删除", exact: true }).click();
    // 第二步确认后：列表空态
    await expect(page.getByText(/还没有会话/)).toBeVisible();
  });

  test("登出：二次确认后回首页", async ({ page }) => {
    await goChat(page);
    await page.getByRole("button", { name: "退出登录" }).click();
    await expect(page.getByRole("dialog", { name: "退出登录" })).toBeVisible();
    await page.getByRole("button", { name: "退出", exact: true }).click();
    await expect(page).toHaveURL(/\/$/);
  });
});
