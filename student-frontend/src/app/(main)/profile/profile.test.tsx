/**
 * 个人中心 /profile 测试（Task 13 TDD 先行用例）
 *
 * 覆盖设计 §1.5.6：
 * - 用户卡：AI 徽标头像（displayName 首字母）+ displayName + 账号 + role 徽章
 * - 我的课程：复用 CourseCard（J1 getMyCourses），四态全覆盖
 * - 退出登录：danger 文字按钮 → logout → 清凭据 → 跳 /login
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import ProfilePage from "./page";
import { ApiError } from "@/lib/api";
import type { StudentCourse } from "@/lib/types";

/** 认证 mock：用户信息与登出 */
const authMock = vi.hoisted(() => ({ useAuth: vi.fn() }));
/** 数据层 mock：已选课程 */
const apiMock = vi.hoisted(() => ({ getMyCourses: vi.fn() }));
/** 路由 mock：退出跳转断言 */
const routerMock = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }));

vi.mock("@/lib/auth-context", () => ({
  useAuth: () => authMock.useAuth(),
}));
vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    getMyCourses: apiMock.getMyCourses,
  };
});
vi.mock("next/navigation", () => ({
  useRouter: () => routerMock,
}));

/** 课程条目工厂（J1 形态） */
function makeCourse(overrides: Partial<StudentCourse> = {}): StudentCourse {
  return {
    id: "c-1",
    title: "数据结构与算法",
    coverImage: "http://localhost:9000/b/c1.jpg",
    category: "计算机",
    instructorName: "王老师",
    duration: "32",
    rating: 4.5,
    learningCount: 256,
    ...overrides,
  };
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ProfilePage />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  authMock.useAuth.mockReset();
  authMock.useAuth.mockReturnValue({
    user: { userId: "u-100", role: "STUDENT", displayName: "张三" },
    accessToken: null,
    isAuthenticated: true,
    isLoading: false,
    login: vi.fn(),
    logout: vi.fn().mockResolvedValue(undefined),
  });
  apiMock.getMyCourses.mockReset();
  routerMock.push.mockReset();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("个人中心：用户卡", () => {
  it("认证加载期（user 为 null）：不渲染用户卡，展示课程骨架", () => {
    authMock.useAuth.mockReturnValue({
      user: null,
      accessToken: null,
      isAuthenticated: false,
      isLoading: true,
      login: vi.fn(),
      logout: vi.fn(),
    });
    apiMock.getMyCourses.mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(screen.getByTestId("courses-skeleton")).toBeInTheDocument();
    expect(screen.queryByTestId("profile-avatar")).not.toBeInTheDocument();
  });

  it("AI 徽标头像（displayName 首字母）+ displayName + 账号 + 学生角色徽章", () => {
    apiMock.getMyCourses.mockResolvedValue([]);
    renderPage();
    // 头像展示 displayName 首字母（设计 §1.5.6 括号语义）
    expect(screen.getByTestId("profile-avatar")).toHaveTextContent("张");
    expect(screen.getByRole("heading", { name: "张三" })).toBeInTheDocument();
    expect(screen.getByText("学生")).toBeInTheDocument();
    expect(screen.getByText(/u-100/)).toBeInTheDocument();
  });

  it("displayName 首字符缺失/空白时头像回退「学」", () => {
    authMock.useAuth.mockReturnValue({
      user: { userId: "u-1", role: "STUDENT", displayName: "" },
      accessToken: null,
      isAuthenticated: true,
      isLoading: false,
      login: vi.fn(),
      logout: vi.fn(),
    });
    apiMock.getMyCourses.mockResolvedValue([]);
    renderPage();
    expect(screen.getByTestId("profile-avatar")).toHaveTextContent("学");
  });

  it("非 STUDENT 角色：徽章原样展示角色名", () => {
    authMock.useAuth.mockReturnValue({
      user: { userId: "u-2", role: "TEACHER", displayName: "李老师" },
      accessToken: null,
      isAuthenticated: true,
      isLoading: false,
      login: vi.fn(),
      logout: vi.fn(),
    });
    apiMock.getMyCourses.mockResolvedValue([]);
    renderPage();
    expect(screen.getByText("TEACHER")).toBeInTheDocument();
  });
});

describe("个人中心：我的课程", () => {
  it("Loading：课程网格骨架", () => {
    apiMock.getMyCourses.mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(screen.getByTestId("courses-skeleton")).toBeInTheDocument();
  });

  it("空态：还没有加入课程，请联系老师开通", async () => {
    apiMock.getMyCourses.mockResolvedValue([]);
    renderPage();
    expect(await screen.findByText("还没有加入课程，请联系老师开通")).toBeInTheDocument();
  });

  it("Error：横幅 + 重试闭环恢复", async () => {
    apiMock.getMyCourses
      .mockRejectedValueOnce(new ApiError(503, "服务暂时不可用"))
      .mockResolvedValueOnce([makeCourse()]);
    renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("服务暂时不可用，请稍后重试");
    fireEvent.click(screen.getByRole("button", { name: "重试" }));
    expect(await screen.findByText("数据结构与算法")).toBeInTheDocument();
  });

  it("正常态：复用 CourseCard 渲染已选课程", async () => {
    apiMock.getMyCourses.mockResolvedValue([
      makeCourse(),
      makeCourse({ id: "c-2", title: "线性代数" }),
    ]);
    renderPage();
    expect(await screen.findByText("数据结构与算法")).toBeInTheDocument();
    expect(screen.getByText("线性代数")).toBeInTheDocument();
    // 整卡为 Link → 各自的课程工作台
    expect(screen.getByRole("link", { name: /数据结构与算法/ })).toHaveAttribute(
      "href",
      "/courses/c-1",
    );
    expect(screen.getByRole("link", { name: /线性代数/ })).toHaveAttribute("href", "/courses/c-2");
  });
});

describe("个人中心：退出登录", () => {
  it("二次确认：点击退出 → 确认框出现 → 确认后 logout、清缓存并跳转 /", async () => {
    apiMock.getMyCourses.mockResolvedValue([]);
    renderPage();
    // 未确认前不登出
    fireEvent.click(screen.getByRole("button", { name: "退出登录" }));
    expect(await screen.findByRole("dialog", { name: "退出登录" })).toBeInTheDocument();
    expect(authMock.useAuth().logout).not.toHaveBeenCalled();
    // 确认退出
    fireEvent.click(screen.getByRole("button", { name: "退出" }));
    await waitFor(() => {
      expect(authMock.useAuth().logout).toHaveBeenCalledTimes(1);
    });
    await waitFor(() => {
      expect(routerMock.push).toHaveBeenCalledWith("/");
    });
  });

  it("取消确认：关闭确认框且不登出", async () => {
    apiMock.getMyCourses.mockResolvedValue([]);
    renderPage();
    fireEvent.click(screen.getByRole("button", { name: "退出登录" }));
    await screen.findByRole("dialog", { name: "退出登录" });
    fireEvent.click(screen.getByRole("button", { name: "取消" }));
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(authMock.useAuth().logout).not.toHaveBeenCalled();
  });

  it("退出登录按钮为 danger 文字样式（danger 类名存在）", async () => {
    apiMock.getMyCourses.mockResolvedValue([]);
    renderPage();
    const button = screen.getByRole("button", { name: "退出登录" });
    expect(button).toHaveTextContent("退出登录");
    expect(button.className).toContain("text-danger");
  });
});
