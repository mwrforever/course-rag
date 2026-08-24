import { test, expect } from "@playwright/test";
import { mockApi, login } from "./helpers/sse-route";

/**
 * 认证流 E2E（整合 spec §3.2 auth 组）
 * - 登录成功跳首页（mock login 200 + Set-Cookie 与后端一致）
 * - 错误凭据 401 → 卡片顶部 Alert「用户名或密码错误」
 * - 未登录访问受保护路由 /chat → middleware 重定向 /login?redirect=
 */

test.describe("认证流", () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page);
  });

  test("登录成功跳转首页", async ({ page }) => {
    await login(page, "/");
    await expect(page.getByRole("heading", { name: "欢迎回来" })).toBeHidden();
    // 首页已加载（顶导存在 + Hero 问候）
    await expect(page.getByText("继续探索你的课程")).toBeVisible();
  });

  test("错误凭据 401 显示 Alert 且停留登录页", async ({ page }) => {
    // 覆盖 mockApi：登录返回 401 错误体
    await page.route("**/api/v1/auth/login", async (route) => {
      await route.fulfill({
        status: 401,
        contentType: "application/json",
        body: JSON.stringify({ code: 401, message: "用户名或密码错误" }),
      });
    });
    await page.goto("/login");
    await page.fill("#username", "student");
    await page.fill("#password", "wrong-pass");
    await page.click('button[type="submit"]');
    // 注：不可用 getByRole('alert')：Next 路由播报器（__next-route-announcer__）同为
    // role=alert 会造成 strict mode 冲突，按文案精确断言
    await expect(page.getByText("用户名或密码错误")).toBeVisible();
    await expect(page).toHaveURL(/\/login$/);
  });

  test("未登录访问 /chat 重定向登录页并携带回跳参数", async ({ page }) => {
    // 不 mock 登录 cookie → middleware 存在性检查拦截
    await page.goto("/chat");
    await expect(page).toHaveURL(/\/login\?redirect=/);
  });

  test("登录后可访问受保护路由（cookie 放行 middleware）", async ({ page }) => {
    await login(page, "/");
    await page.goto("/chat");
    await expect(page).toHaveURL(/\/chat$/);
  });
});
