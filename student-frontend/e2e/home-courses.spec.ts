import { test, expect } from "@playwright/test";
import { mockApi, login } from "./helpers/sse-route";

/**
 * 首页与课程页 E2E（公开化 2026-08-26 修订；详情页改版 2026-08-31）
 * - 首页/课堂页公开可浏览（public/courses 数据源，未登录不拦截）
 * - 首页无最近会话区块（会话管理归课程助手侧边栏）；快问框入口
 * - 课程详情页为登录门槛：middleware 门控游客（未登录直引登录页，无自动弹窗）
 * - 登录用户：详情页完整课程信息（介绍 + 开课时间 + 课时）+ 已购徽章
 *   （资料分片/403 引导态已随改版移除，购买流归 course-purchase.spec）
 */

test.describe("首页与课程", () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page);
  });

  test("首页公开渲染：问渠学堂品牌壳 + 精选课程卡 + 能力手风琴，无最近会话区块", async ({
    page,
  }) => {
    // 不登录直接浏览（公开化核心契约）
    await page.goto("/");
    // 设计稿一品牌壳就位：巨型 Hero 字 + 顶栏 Logo
    await expect(page.locator("#top")).toBeVisible();
    // 公开课程数据源渲染为精选课程大卡（web-first 自动等待）
    await expect(page.getByText("数据结构与算法精讲")).toBeVisible();
    await expect(page.getByText("Java 从入门到进阶")).toBeVisible();
    // 能力手风琴四项（业务替换后的平台真实能力）
    await expect(page.getByTestId("service-head")).toHaveCount(4);
    // 最近会话区块已移除（首页不再出现会话条目）
    await expect(page.getByText("数据结构与算法咨询")).toHaveCount(0);
    await expect(page.getByText("最近会话")).toHaveCount(0);
  });

  test("空课程列表显示引导空态（公开源）", async ({ page }) => {
    await page.route("**/api/v1/public/courses", async (route) => {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ code: 0, message: "success", data: [] }),
      });
    });
    await page.goto("/");
    await expect(page.getByText("暂无上架课程，请稍后再来")).toBeVisible();
  });

  test("课程列表本地筛选与关键词过滤（已购徽章）", async ({ page }) => {
    await login(page, "/");
    await page.goto("/courses");
    await expect(page.getByText("数据结构与算法精讲")).toBeVisible();
    // 登录用户经我的课程交叉：两门课均「已购」（契约 H.2.1 替代原已加入语义）
    await expect(page.getByText("已购")).toHaveCount(2);
    // 关键词过滤（即时，无 debounce）
    await page.getByRole("searchbox", { name: "搜索课程" }).fill("Java");
    await expect(page.getByText("数据结构与算法精讲")).toBeHidden();
    await expect(page.getByText("Java 从入门到进阶")).toBeVisible();
  });

  test("未登录访问 /courses 与详情页：middleware 直引登录页并携带 next（2026-08-27 仅首页公开）", async ({
    page,
  }) => {
    // 不登录访问课程中心：重定向 /login?next=/courses
    await page.goto("/courses");
    await expect(page).toHaveURL(/\/login\?next=%2Fcourses$/);
    // 深链详情页同样拦截并携带原路径
    await page.goto("/courses/1");
    await expect(page).toHaveURL(/\/login\?next=%2Fcourses%2F1$/);
  });

  test("登录后课程详情页：完整课程信息（介绍 + 开课时间 + 课时），无自动登录弹窗", async ({
    page,
  }) => {
    await login(page, "/");
    await page.goto("/courses/1");
    // 公开课程信息即时可见（Hero 标题 + 元信息行）
    await expect(page.getByRole("heading", { name: "数据结构与算法精讲" })).toBeVisible();
    await expect(page.getByText("张老师")).toBeVisible();
    await expect(page.getByText("12 课时")).toBeVisible();
    // 已登录：不再弹出登录窗
    await expect(page.getByRole("dialog", { name: "登录课程助手" })).toBeHidden();
    // 课程介绍：完整描述渲染（改版后详情页主体）
    await expect(page.getByRole("heading", { name: "课程介绍" })).toBeVisible();
    await expect(page.getByText("从线性表到图论的系统课程")).toBeVisible();
    // 开课信息：排期卡片（课程 1 预置一期排期，开课/结课日期中文展示）
    await expect(page.getByRole("heading", { name: "开课信息" })).toBeVisible();
    await expect(page.getByText("2026 年 9 月 1 日")).toBeVisible();
    await expect(page.getByText("未开课")).toBeVisible();
    // 改版回归：被移除的按钮与资料分片区不再渲染
    await expect(page.getByRole("button", { name: /问 AI 助教/ })).toHaveCount(0);
    await expect(page.getByRole("link", { name: /进入学习/ })).toHaveCount(0);
    await expect(page.getByRole("link", { name: /浏览资料/ })).toHaveCount(0);
    await expect(page.getByText("课程资料")).toHaveCount(0);
  });

  test("详情页无排期：展示「暂无排期信息」空态（课程 2 未录入排期）", async ({ page }) => {
    await login(page, "/");
    await page.goto("/courses/2");
    await expect(page.getByRole("heading", { name: "Java 从入门到进阶" })).toBeVisible();
    await expect(page.getByTestId("schedule-empty")).toBeVisible();
    await expect(page.getByText("暂无排期信息，敬请期待")).toBeVisible();
  });
});
