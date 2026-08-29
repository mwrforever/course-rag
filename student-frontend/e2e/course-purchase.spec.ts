import { test, expect, type Page } from "@playwright/test";
import { mockApi, login } from "./helpers/sse-route";

/**
 * 课程购买链路 E2E（契约 B/H.2.2/H.2.3，2026-08-29；route-mock 模式不依赖真实后端）
 *
 * 覆盖：
 * - 完整购买流：未购 403 引导态（还未购买该课程）→ 立即购买 → 成功 toast →
 *   已购徽章 + 进入学习入口 + 资料区由不可访问变为可访问（失效重取闭环）
 * - 未登录引导：middleware 拦截携带 next 跳登录（D1 方案 B：购买自然要求登录；
 *   登录弹窗 afterLogin 自动续购交互由 Vitest 单测覆盖——middleware 使游客无法抵达详情页）
 * - 重复购买幂等：后端对已购课程再次购买返回相同成功结构，前端不预拦截、无错误横幅
 *
 * mock 时序约定：mockApi 先注册（beforeEach），用例内后注册的同路径 handler 优先生效
 * （Playwright 路由匹配后注册者优先），以 purchased 标记驱动 403→可访问、未购→已购翻转。
 */

/** 我的课程条目 mock（课程 1：已购基线；课程 2：购买流目标课程） */
const MY_COURSE_1 = {
  id: "1",
  title: "数据结构与算法精讲",
  coverImage: null,
  category: "编程",
  instructorName: "张老师",
  duration: 12,
  rating: 4.8,
  learningCount: 236,
  price: 299,
};
const MY_COURSE_2 = {
  id: "2",
  title: "Java 从入门到进阶",
  coverImage: null,
  category: "编程",
  instructorName: "李老师",
  duration: 20,
  rating: 4.5,
  learningCount: 89,
  price: 0,
};

/** 购买成功响应体（契约 B：幂等——已购再购返回相同成功结构） */
const PURCHASE_OK = (courseId: string) =>
  JSON.stringify({
    code: 0,
    message: "success",
    data: { courseId, status: "ACTIVE", purchased: true },
  });

/** 资料 mock 数据（购买成功后资料区渲染的分片） */
const MATERIAL_CHUNK = {
  id: "201",
  content: "Java 面向对象的第一课：类与对象的系统讲解。",
  headingPath: "第1章 > 1.1 类与对象",
  chunkIndex: 1,
  parentTitle: "第1章 面向对象基础",
  startPage: 2,
  endPage: 4,
};

/**
 * 注册课程 2 购买流的三端点 mock（在 mockApi 之后调用以获得路由优先权）
 *
 * @param page 页面对象
 * @param options.purchasedRef 可变的已购标记引用（购买端点命中后置真）
 * @param options.materialsBefore 购买前资料响应（"forbidden"=403 引导态；"ok"=直接可访问）
 * @param options.myCoursesAlwaysStale 我的课程恒不含课程 2（幂等用例模拟刷新滞后，按钮不消失）
 * @param options.purchaseCallsRef 记录购买请求次数的引用（幂等断言用）
 */
function mockPurchaseFlow(
  page: Page,
  options: {
    purchasedRef: { value: boolean };
    materialsBefore: "forbidden" | "ok";
    myCoursesAlwaysStale?: boolean;
    purchaseCallsRef?: { value: number };
  },
) {
  const { purchasedRef, materialsBefore, myCoursesAlwaysStale, purchaseCallsRef } = options;

  // 我的课程：purchased 翻转后包含课程 2（invalidate 重取命中已购态）；
  // 幂等用例恒不含（刷新滞后）以保留购买入口供二次点击
  void page.route("**/api/v1/student/courses", async (route) => {
    const includeCourse2 = purchasedRef.value && !myCoursesAlwaysStale;
    const data = includeCourse2 ? [MY_COURSE_1, MY_COURSE_2] : [MY_COURSE_1];
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ code: 0, message: "success", data }),
    });
  });

  // 课程 2 资料：购买前 403（未购无权限）或直接可访问（幂等用例走 Hero 购买路径）
  void page.route("**/api/v1/student/courses/2/materials", async (route) => {
    if (!purchasedRef.value && materialsBefore === "forbidden") {
      await route.fulfill({
        status: 403,
        contentType: "application/json",
        body: JSON.stringify({ code: 403, message: "未选修该课程" }),
      });
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ code: 0, message: "success", data: [MATERIAL_CHUNK] }),
    });
  });

  // 购买端点：置已购标记并返回幂等成功结构
  void page.route("**/api/v1/student/courses/2/purchase", async (route) => {
    purchasedRef.value = true;
    if (purchaseCallsRef) {
      purchaseCallsRef.value += 1;
    }
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: PURCHASE_OK("2"),
    });
  });
}

test.describe("课程购买链路", () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page);
  });

  test("完整购买流：403 引导态立即购买 → 成功 toast → 已购视图 + 资料可访问", async ({ page }) => {
    const purchasedRef = { value: false };
    mockPurchaseFlow(page, { purchasedRef, materialsBefore: "forbidden" });
    await login(page, "/");
    await page.goto("/courses/2");

    // 未购买 403 引导态：新文案 + 立即购买入口（契约 H.2.3）
    await expect(page.getByText("还未购买该课程")).toBeVisible();
    await expect(page.getByRole("button", { name: /立即购买/ })).toBeVisible();

    // 立即购买：成功 toast + 已购徽章 + 进入学习入口（H.2.2 状态机已购态）
    await page.getByRole("button", { name: /立即购买/ }).click();
    await expect(page.getByText("购买成功")).toBeVisible();
    await expect(page.getByTestId("purchased-badge")).toBeVisible();
    await expect(page.getByRole("link", { name: /进入学习/ })).toBeVisible();

    // 资料区由不可访问（403 空态）变为可访问：分片渲染 + 空态消失
    await expect(page.getByText("Java 面向对象的第一课：类与对象的系统讲解。")).toBeVisible();
    await expect(page.getByText("还未购买该课程")).toHaveCount(0);
  });

  test("未登录访问详情页：middleware 拦截并携带 next 跳登录（购买要求登录）", async ({ page }) => {
    // D1 方案 B：/courses/[id] 登录可见，游客购买入口即登录引导（afterLogin 自动续购由单测覆盖）
    await page.goto("/courses/2");
    await expect(page).toHaveURL(/\/login\?next=%2Fcourses%2F2$/);
  });

  test("重复购买幂等：再次购买返回相同成功结构，无错误横幅（前端不预拦截）", async ({ page }) => {
    const purchasedRef = { value: false };
    const purchaseCallsRef = { value: 0 };
    // 资料 mock 直接可访问（走 Hero 购买路径）；我的课程恒滞后（不含课程 2）保留二次购买入口
    mockPurchaseFlow(page, {
      purchasedRef,
      materialsBefore: "ok",
      myCoursesAlwaysStale: true,
      purchaseCallsRef,
    });
    await login(page, "/");
    await page.goto("/courses/2");

    // Hero 价格（课程 2 免费）+ 购买按钮就位
    await expect(page.getByTestId("course-price")).toHaveText("免费");
    const buyButton = page.getByRole("button", { name: "购买课程" });
    await expect(buyButton).toBeVisible();

    // 首次购买成功
    await buyButton.click();
    await expect(page.getByText("购买成功")).toBeVisible();
    expect(purchaseCallsRef.value).toBe(1);

    // my-courses mock 恒不含课程 2（刷新滞后）→ 按钮恢复：再次购买验证后端幂等语义
    const secondPurchase = page.waitForResponse(
      (response) =>
        response.url().includes("/student/courses/2/purchase") &&
        response.request().method() === "POST",
    );
    await expect(buyButton).toBeEnabled();
    await buyButton.click();
    await secondPurchase;
    expect(purchaseCallsRef.value).toBe(2);
    // 幂等成功不产生错误横幅（无 409 / 下架提示）。
    // 断言收窄到「含文本的 alert」：Next.js DevTools（开发态门户 shadow DOM）自带
    // 空的 role=alert live-region，getByRole 可穿透 shadow DOM 而 DOM 查询不可见，
    // 宽断言在复用 dev server 的本地 E2E 会确定性误报（N5 实证修复 2026-08-29）
    await expect(page.getByRole("alert").filter({ hasText: /\S/ })).toHaveCount(0);
  });
});
