import { test, expect } from "@playwright/test";
import { mockApi, login } from "./helpers/sse-route";

/**
 * 首页与课程页 E2E（整合 spec §3.2 首页/课程组）
 * - J1 mock 渲染课程卡网格 + 空态引导 + 本地筛选行为
 */

test.describe("首页与课程", () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page);
  });

  test("首页渲染课程 Bento 网格与最近会话", async ({ page }) => {
    await login(page, "/");
    // 两门课：首卡宽幅 2x1（n<=2 降级）+ 次卡 + 资料库入口条
    await expect(page.getByText("数据结构与算法精讲")).toBeVisible();
    await expect(page.getByText("Java 从入门到进阶")).toBeVisible();
    await expect(page.getByText("数据结构与算法咨询")).toBeVisible();
  });

  test("空课程列表显示引导空态", async ({ page }) => {
    await page.route("**/api/v1/student/courses", async (route) => {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ code: 0, message: "success", data: [] }),
      });
    });
    await login(page, "/");
    await expect(page.getByText("还没有加入课程，请联系老师开通")).toBeVisible();
  });

  test("课程列表本地筛选与关键词过滤", async ({ page }) => {
    await login(page, "/");
    await page.goto("/courses");
    await expect(page.getByText("数据结构与算法精讲")).toBeVisible();
    // 关键词过滤（即时，无 debounce）
    await page.getByRole("searchbox", { name: "搜索课程" }).fill("Java");
    await expect(page.getByText("数据结构与算法精讲")).toBeHidden();
    await expect(page.getByText("Java 从入门到进阶")).toBeVisible();
  });

  test("课程工作台渲染资料分片与上下文抽屉", async ({ page }) => {
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
