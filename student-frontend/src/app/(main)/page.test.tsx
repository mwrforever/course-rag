/**
 * 首页测试（问渠学堂重构 2026-08-27：设计稿一区块化还原）
 *
 * 覆盖：品牌与结构冒烟（Hero 巨字 / 能力手风琴四项 / 上手指引六卡 / 快捷宫格 /
 * AI 助教 FAB）；精选课程四态（Loading / 正常含已加入徽章交叉 / Empty / Error 重试）；
 * 公开浏览契约（未登录无徽章、my-courses 不请求）。
 * 数据层以 vi.mock 注入（react-query 用 QueryClient 包裹并关闭 retry）。
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import HomePage from "./page";
import type { PublicCourse } from "@/lib/types";

/** 数据层 mock：getPublicCourses（公开源）/ getMyCourses（已加入交叉） */
const apiMock = vi.hoisted(() => ({ getPublicCourses: vi.fn(), getMyCourses: vi.fn() }));
/** 认证 mock：登录态可切换 + 弹窗操作记录 */
const authMock = vi.hoisted(() => ({ useAuth: vi.fn() }));
/** 路由 mock：push/replace 记录 */
const routerMock = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }));

vi.mock("@/lib/api", () => ({
  getPublicCourses: apiMock.getPublicCourses,
  getMyCourses: apiMock.getMyCourses,
}));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authMock.useAuth() }));
vi.mock("next/navigation", () => ({ useRouter: () => routerMock }));

/** 构造公开课程对象（PublicCourseVO 各字段形态可覆盖） */
function makeCourse(overrides: Partial<PublicCourse> = {}): PublicCourse {
  return {
    id: "c-1",
    title: "数据结构与算法",
    description: "入门课程",
    coverImage: null,
    category: "计算机",
    instructorName: "王老师",
    duration: "32",
    rating: 4.5,
    learningCount: 256,
    ...overrides,
  };
}

/** 渲染容器：独立 QueryClient（retry 关闭），用例间不共享缓存 */
function renderHome() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <HomePage />
    </QueryClientProvider>,
  );
}

/** 默认认证返回值：已登录同学A + 弹窗 API（用例内覆盖） */
function defaultAuth(overrides: Record<string, unknown> = {}) {
  return {
    user: { userId: "u1", role: "STUDENT", displayName: "同学A" },
    accessToken: null,
    isAuthenticated: true,
    isLoading: false,
    login: vi.fn(),
    logout: vi.fn(),
    loginDialogOpen: false,
    openLoginDialog: vi.fn(),
    closeLoginDialog: vi.fn(),
    submitLogin: vi.fn(),
    ...overrides,
  };
}

beforeEach(() => {
  apiMock.getPublicCourses.mockReset();
  apiMock.getMyCourses.mockReset();
  authMock.useAuth.mockReset();
  routerMock.push.mockReset();
  routerMock.replace.mockReset();
  apiMock.getPublicCourses.mockResolvedValue([]);
  apiMock.getMyCourses.mockResolvedValue([]);
  authMock.useAuth.mockReturnValue(defaultAuth());
  window.history.replaceState(null, "", "/");
});

afterEach(() => {
  vi.clearAllTimers?.();
});

describe("首页品牌与结构冒烟", () => {
  it("Hero 巨字品牌名 + 能力手风琴四项 + 指引卡六张 + 宫格入口 + AI 助教 FAB 全部就位", async () => {
    renderHome();
    // Hero 巨型衬线品牌字（顶栏 Logo 属布局层，不在本测试范围）
    expect(screen.getAllByText("问渠学堂").length).toBe(1);
    // 平台能力手风琴（真实能力业务替换：AI 课程问答等四项）
    const serviceHeads = await screen.findAllByTestId("service-head");
    expect(serviceHeads.length).toBe(4);
    expect(serviceHeads[0]).toHaveTextContent("AI 课程问答");
    // 上手指引横滑区六张卡，全部指向真实功能路由
    const posts = screen.getAllByTestId("hub-post");
    expect(posts.length).toBe(6);
    for (const post of posts) {
      const href = post.getAttribute("href") ?? "";
      expect(["/chat", "/courses", "/profile"]).toContain(href);
    }
    // 快捷入口宫格与右下 AI 助教浮动按钮
    expect(screen.getAllByTestId("entry-tile").length).toBe(5);
    expect(screen.getByTestId("assistant-fab")).toHaveAttribute("aria-label", "打开 AI 课程助教");
  });

  it("点击手风琴项展开正文且互斥收合（服务区交互契约）", async () => {
    renderHome();
    const heads = await screen.findAllByTestId("service-head");
    fireEvent.click(heads[0]);
    expect(heads[0]).toHaveAttribute("aria-expanded", "true");
    fireEvent.click(heads[1]);
    // 单选互斥：展开第二项时第一项自动收合
    expect(heads[1]).toHaveAttribute("aria-expanded", "true");
    expect(heads[0]).toHaveAttribute("aria-expanded", "false");
  });
});

describe("精选课程四态", () => {
  it("Loading：待响应阶段不渲染课程卡（骨架由布局脉冲块承担）", async () => {
    apiMock.getPublicCourses.mockReturnValue(new Promise(() => {}));
    renderHome();
    expect(screen.queryByTestId("wenqu-course-card")).toBeNull();
  });

  it("正常态：公开课程大卡渲染，登录用户显示「已加入」交叉标记", async () => {
    apiMock.getPublicCourses.mockResolvedValue([
      makeCourse({ id: "c-1" }),
      makeCourse({ id: "c-2", title: "信号与系统", category: null }),
    ]);
    apiMock.getMyCourses.mockResolvedValue([{ ...makeCourse({ id: "c-1" }) }] as never);
    renderHome();
    await waitFor(() => expect(screen.getAllByTestId("wenqu-course-card").length).toBe(2));
    // 已加入徽章仅对 c-1 显示
    expect(screen.getAllByText("已加入").length).toBe(1);
    expect(await screen.findByText("信号与系统")).toBeInTheDocument();
  });

  it("未登录：不请求我的课程且无已加入徽章（公开浏览契约）", async () => {
    authMock.useAuth.mockReturnValue(defaultAuth({ user: null, isAuthenticated: false }));
    apiMock.getPublicCourses.mockResolvedValue([makeCourse()]);
    renderHome();
    expect(await screen.findByTestId("wenqu-course-card")).toBeInTheDocument();
    expect(apiMock.getMyCourses).not.toHaveBeenCalled();
    expect(screen.queryByText("已加入")).toBeNull();
  });

  it("Empty：空态引导去和 AI 助教聊聊", async () => {
    apiMock.getPublicCourses.mockResolvedValue([]);
    renderHome();
    expect(await screen.findByText("暂无上架课程，请稍后再来")).toBeInTheDocument();
  });

  it("Error：错误横幅可重试（refetch 再次调用数据源）", async () => {
    apiMock.getPublicCourses.mockRejectedValue(new Error("网络异常"));
    renderHome();
    const retry = await screen.findByRole("button", { name: /重试/ });
    expect(retry).toBeInTheDocument();
    apiMock.getPublicCourses.mockResolvedValue([makeCourse()]);
    fireEvent.click(retry);
    expect(await screen.findByTestId("wenqu-course-card")).toBeInTheDocument();
  });
});
