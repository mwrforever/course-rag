import { test, expect } from "@playwright/test";
import { mockApi, login } from "./helpers/sse-route";

/**
 * 认证流 E2E（独立登录页回归 + 邮箱注册两段式 2026-08-27）
 * - 独立 /login 页：登录成功跳首页且顶栏出现头像；?tab=register 直达注册面板
 * - 错误凭据 401 → 独立页内 Alert「用户名或密码错误」且停留在登录页
 * - 注册两段式：发码 → 倒计时文案；填码提交（错误码 400 停留 / 正确码自动登录回首页）
 * - 未登录访问受保护路由 /chat → middleware 重定向首页 ?login=1 → 自动开弹窗（旧契约保留）
 * - 已登录（cookie + RT 静默续期）可访问受保护路由
 */

test.describe("认证流（独立登录页 + 注册）", () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page);
  });

  test("登录成功：独立页提交 → 跳首页且顶栏出现用户头像", async ({ page }) => {
    await page.goto("/login");
    // 设计稿二结构就位：左右分栏影像区可见（lg 视口）
    await expect(page.getByTestId("login-visual")).toBeVisible();
    await page.getByTestId("login-account-input").fill("student");
    await page.getByTestId("login-password-input").fill("123456");
    await page.getByTestId("login-submit").click();
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByTestId("header-avatar")).toBeVisible();
  });

  test("错误凭据 401：独立页 Alert 分级文案且停留登录页", async ({ page }) => {
    await page.route("**/api/v1/auth/login", async (route) => {
      await route.fulfill({
        status: 401,
        contentType: "application/json",
        body: JSON.stringify({ code: 401, message: "用户名或密码错误" }),
      });
    });
    await page.goto("/login");
    await page.getByTestId("login-account-input").fill("student");
    await page.getByTestId("login-password-input").fill("wrong-pass");
    await page.getByTestId("login-submit").click();
    await expect(page.getByText("用户名或密码错误")).toBeVisible();
    await expect(page).toHaveURL(/\/login/);
  });

  test("?tab=register 直达注册面板：Tab 切换与指示器联动", async ({ page }) => {
    await page.goto("/login?tab=register");
    await expect(page.getByTestId("register-panel")).toBeVisible();
    await expect(page.getByTestId("tab-register")).toHaveAttribute("aria-selected", "true");
    // 切回登录面板：URL 参数同步清除（可分享语义）
    await page.getByTestId("tab-signin").click();
    await expect(page.getByTestId("login-panel")).toBeVisible();
    await expect(page).not.toHaveURL(/[?&]tab=register/);
  });

  test("注册两段式：发码按钮进入倒计时，提交后自动登录回首页", async ({ page }) => {
    await page.goto("/login?tab=register");
    const sendButton = page.getByTestId("send-code-button");
    // 步骤一：先以非法邮箱点击，拦在本面板不发请求
    await page.getByTestId("reg-email-input").fill("student@example.com");
    await sendButton.click();
    // 后端频控窗口开启（mock 即时 200）：按钮进入倒计时不可再点
    await expect(sendButton).toBeDisabled();
    await expect(sendButton).toHaveText(/\d+s 后重发/);

    // 步骤二：完整注册信息提交（昵称可选不填），正确验证码走通自动登录
    await page.getByTestId("reg-code-input").fill("654321");
    await page.getByTestId("reg-password-input").fill("Password-88");
    await page.getByTestId("terms-row").click();
    await page.getByTestId("register-submit").click();
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByTestId("header-avatar")).toBeVisible();
  });

  test("注册错误验证码：400 提示且停留登录页", async ({ page }) => {
    await page.goto("/login?tab=register");
    await page.getByTestId("reg-email-input").fill("student@example.com");
    await page.getByTestId("reg-code-input").fill("000000");
    await page.getByTestId("reg-password-input").fill("Password-88");
    await page.getByTestId("terms-row").click();
    await page.getByTestId("register-submit").click();
    await expect(page.getByText("验证码错误")).toBeVisible();
    await expect(page).toHaveURL(/\/login/);
  });

  test("未登录访问 /chat：middleware 直引独立登录页携带 next，登录后站内回跳", async ({ page }) => {
    await page.goto("/chat");
    // m4 审查修订：深度链路兜底直引独立登录页并携带原路径（替代旧 ?login=1 首页弹窗契约）
    await expect(page).toHaveURL(/\/login\?next=%2Fchat/);
    await page.getByTestId("login-account-input").fill("student");
    await page.getByTestId("login-password-input").fill("123456");
    await page.getByTestId("login-submit").click();
    // next 白名单校验通过：回到原受保护路由，且登录响应已种下 middleware 门卫所需 AT cookie
    await expect(page).toHaveURL(/\/chat$/);
    const authCookie = (await page.context().cookies()).find(
      (cookie) => cookie.name === "commerce_token",
    );
    expect(authCookie?.value).toBe("at-e2e");
  });

  test("登录后可访问受保护路由（cookie 放行 middleware）", async ({ page }) => {
    await login(page, "/");
    await page.goto("/chat");
    await expect(page).toHaveURL(/\/chat$/);
  });
});
