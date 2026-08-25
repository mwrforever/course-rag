import { test, expect } from "@playwright/test";
import { mockApi, login } from "./helpers/sse-route";

/**
 * 课程助手侧栏 E2E（UI 重构 2026-08-25 新增 kimi 式壳层）
 *
 * - 会话历史渲染：显式走 mockApi 的 GET /student/sessions 契约（非 catch-all 兜底），
   锁定「新增侧栏查询不破坏既有 chat 页面」的回归保护
 * - 折叠/展开：宽度类切换（w-64↔w-16）+ localStorage 持久化跨 reload 保持
 */

test.describe("课程助手侧栏", () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page);
  });

  test("侧栏渲染会话历史，折叠态刷新后保持", async ({ page }) => {
    await login(page, "/");
    await page.goto("/chat");

    const sidebar = page.getByTestId("chat-sidebar");
    await expect(sidebar).toBeVisible();
    await expect(sidebar).toHaveClass(/w-64/);
    // 会话历史条目来自显式 mock 的会话列表（mockApi records=1）
    await expect(page.getByTestId("sidebar-session-item")).toHaveCount(1);

    // 折叠：宽度切换为图标态
    await page.getByRole("button", { name: "收起侧栏" }).click();
    await expect(sidebar).toHaveClass(/w-16/);
    await expect(sidebar).not.toHaveClass(/w-64/);

    // 刷新后仍折叠：折叠偏好经 localStorage 持久化（初始渲染展开 → effect 读偏好翻转，
    // web-first 断言自动轮询至稳定态）
    await page.reload();
    await expect(page.getByTestId("chat-sidebar")).not.toHaveClass(/w-64/);
    await expect(page.getByTestId("chat-sidebar")).toHaveClass(/w-16/);

    // 再展开恢复常规宽度
    await page.getByRole("button", { name: "展开侧栏" }).click();
    await expect(page.getByTestId("chat-sidebar")).toHaveClass(/w-64/);
  });
});
