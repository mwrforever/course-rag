import { test, expect } from "@playwright/test";
import { mockApi, login } from "./helpers/sse-route";

/**
 * 认证流 E2E（登录弹窗化 2026-08-26 修订）
 * - 首页快问框触发登录弹窗 → 登录成功 → afterLogin 继续跳转 /chat?q=
 * - 错误凭据 401 → 弹窗内 Alert「用户名或密码错误」且弹窗保持
 * - 未登录访问受保护路由 /chat → middleware 重定向首页 ?login=1 → 自动开弹窗
 * - 已登录（cookie + RT 静默续期）可访问受保护路由
 */

test.describe("认证流（登录弹窗）", () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page);
  });

  /** 经快问框触发登录弹窗（未登录态） */
  async function openLoginDialog(page: import("@playwright/test").Page) {
    await page.goto("/");
    await page.fill("#quick-question", "什么是索引下推");
    await page.click('button[type="submit"]');
    await expect(page.getByRole("dialog", { name: "登录课程助手" })).toBeVisible();
  }

  test("登录成功：弹窗提交 → 关闭弹窗并继续跳转 /chat?q=", async ({ page }) => {
    await openLoginDialog(page);
    await page.fill("#login-username", "student");
    await page.fill("#login-password", "123456");
    await page
      .getByRole("dialog", { name: "登录课程助手" })
      .getByRole("button", { name: "登录", exact: true })
      .click();
    // afterLogin 生效：继续快速提问跳转（预填问题）
    await expect(page).toHaveURL(/\/chat\?q=/);
    await expect(page.getByRole("dialog", { name: "登录课程助手" })).toBeHidden();
    // 对话页已落位：工作区问候 + 输入框预填问题
    await expect(page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行")).toHaveValue(
      "什么是索引下推",
    );
  });

  test("错误凭据 401：弹窗内 Alert 分级文案且弹窗保持", async ({ page }) => {
    // 覆盖 mockApi：登录返回 401 错误体
    await page.route("**/api/v1/auth/login", async (route) => {
      await route.fulfill({
        status: 401,
        contentType: "application/json",
        body: JSON.stringify({ code: 401, message: "用户名或密码错误" }),
      });
    });
    await openLoginDialog(page);
    await page.fill("#login-username", "student");
    await page.fill("#login-password", "wrong-pass");
    await page
      .getByRole("dialog", { name: "登录课程助手" })
      .getByRole("button", { name: "登录", exact: true })
      .click();
    // 注：不可用 getByRole('alert')：Next 路由播报器（__next-route-announcer__）同为
    // role=alert 会造成 strict mode 冲突，按文案精确断言
    await expect(page.getByText("用户名或密码错误")).toBeVisible();
    await expect(page.getByRole("dialog", { name: "登录课程助手" })).toBeVisible();
  });

  test("未登录访问 /chat：middleware 回首页 ?login=1 并自动打开登录弹窗", async ({ page }) => {
    await page.goto("/chat");
    await expect(page).toHaveURL(/\?login=1/);
    await expect(page.getByRole("dialog", { name: "登录课程助手" })).toBeVisible();
  });

  test("登录后可访问受保护路由（cookie 放行 middleware）", async ({ page }) => {
    await login(page, "/");
    await page.goto("/chat");
    await expect(page).toHaveURL(/\/chat$/);
  });
});
