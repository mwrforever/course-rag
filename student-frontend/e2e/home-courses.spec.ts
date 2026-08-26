import { test, expect } from "@playwright/test";
import { mockApi, login } from "./helpers/sse-route";

/**
 * 首页与课程页 E2E（公开化 2026-08-26 修订）
 * - 首页/课堂页公开可浏览（public/courses 数据源，未登录不拦截）
 * - 首页无最近会话区块（会话管理归课程助手侧边栏）；快问框入口
 * - 课程详情页为登录门槛：未登录自动弹登录窗 + 资料区登录墙
 * - 登录用户：已加入徽章 + 资料分片/403 引导态（原契约保留）
 */

test.describe("首页与课程", () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page);
  });

  test("首页公开渲染：推荐课程 + 快问框，无最近会话区块", async ({ page }) => {
    // 不登录直接浏览（公开化核心契约）
    await page.goto("/");
    await expect(page.getByText("数据结构与算法精讲")).toBeVisible();
    await expect(page.getByText("Java 从入门到进阶")).toBeVisible();
    // 快问框（核心入口）
    await expect(page.getByLabel("快速提问")).toBeVisible();
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

  test("课程列表本地筛选与关键词过滤（已加入徽章）", async ({ page }) => {
    await login(page, "/");
    await page.goto("/courses");
    await expect(page.getByText("数据结构与算法精讲")).toBeVisible();
    // 登录用户经我的课程交叉：两门课均「已加入」
    await expect(page.getByText("已加入")).toHaveCount(2);
    // 关键词过滤（即时，无 debounce）
    await page.getByRole("searchbox", { name: "搜索课程" }).fill("Java");
    await expect(page.getByText("数据结构与算法精讲")).toBeHidden();
    await expect(page.getByText("Java 从入门到进阶")).toBeVisible();
  });

  test("未登录课程详情页：公开信息可浏览 + 自动弹登录窗 + 资料登录墙", async ({ page }) => {
    await page.goto("/courses/1");
    // 公开课程信息即时可见（Hero 标题 + 简介）
    await expect(page.getByRole("heading", { name: "数据结构与算法精讲" })).toBeVisible();
    await expect(page.getByText("从线性表到图论的系统课程")).toBeVisible();
    // 登录门槛：自动弹窗（可关闭）+ 资料区登录墙
    await expect(page.getByRole("dialog", { name: "登录课程助手" })).toBeVisible();
    await expect(page.getByTestId("login-gate")).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(page.getByRole("dialog", { name: "登录课程助手" })).toBeHidden();
  });

  test("课程工作台渲染资料分片与上下文抽屉（已登录）", async ({ page }) => {
    // 材料与上下文 mock
    await page.route("**/api/v1/student/courses/1/materials", async (route) => {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          code: 0,
          message: "success",
          data: [
            {
              id: "101",
              content: "本章介绍线性表的基本概念与顺序存储实现。",
              headingPath: "第1章 > 1.2 顺序表",
              chunkIndex: 3,
              parentTitle: "第1章 线性表",
              startPage: 12,
              endPage: 15,
            },
          ],
        }),
      });
    });
    await page.route("**/api/v1/student/chunks/101/context", async (route) => {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          code: 0,
          message: "success",
          data: {
            parent: {
              id: "100",
              content: "线性表是数据结构的基础。",
              headingPath: "第1章",
              chunkIndex: 1,
              parentTitle: null,
            },
            prev: null,
            next: null,
          },
        }),
      });
    });
    await login(page, "/");
    await page.goto("/courses/1");
    // headingPath 拆分渲染（段间以「 / 」分隔，design §1.5.3 面包屑），按末段精确断言
    await expect(page.getByText("1.2 顺序表")).toBeVisible();
    // 打开上下文抽屉：父章节卡渲染、null 节点不渲染
    await page.getByRole("button", { name: /查看上下文/ }).click();
    await expect(page.getByText("线性表是数据结构的基础。")).toBeVisible();
    await expect(page.getByText("该分片暂无上下文关联")).toBeHidden();
  });

  test("未选课访问课程工作台显示引导态（403）", async ({ page }) => {
    await page.route("**/api/v1/student/courses/1/materials", async (route) => {
      await route.fulfill({
        status: 403,
        contentType: "application/json",
        body: JSON.stringify({ code: 403, message: "未选修该课程" }),
      });
    });
    await login(page, "/");
    await page.goto("/courses/1");
    await expect(page.getByText("还没有加入这门课程，请联系老师开通")).toBeVisible();
  });
});
