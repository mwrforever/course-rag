/**
 * 我的课程页 /my-courses 测试（2026-08-31 用户拍板新增）
 *
 * 覆盖：
 * - 页头（标题 + 已购数量）与已购课程网格（复用 CourseCard，已购徽章态）
 * - 四态：Loading 骨架 / 空态（引导去课程中心）/ 错误重试 / 正常态
 * - AuthGate 守卫：未认证期间渲染骨架 + 打开登录弹窗；登录后渲染业务内容
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import MyCoursesPage from "./page";
import { ApiError } from "@/lib/api";
import type { StudentCourse } from "@/lib/types";

/** 认证 mock：登录态可切换 + 弹窗操作记录（AuthGate 依赖） */
const authMock = vi.hoisted(() => ({ useAuth: vi.fn() }));
/** 数据层 mock：已购课程 */
const apiMock = vi.hoisted(() => ({ getMyCourses: vi.fn() }));

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
    price: 299,
    ...overrides,
  };
}

/** 默认认证返回值：已登录（未认证用例覆盖开关） */
function defaultAuth(overrides: Record<string, unknown> = {}) {
  return {
    user: { userId: "u-100", role: "STUDENT", displayName: "张三" },
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

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MyCoursesPage />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  authMock.useAuth.mockReset();
  authMock.useAuth.mockReturnValue(defaultAuth());
  apiMock.getMyCourses.mockReset();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("我的课程页：四态", () => {
  it("Loading：课程网格骨架 + 页头标题", async () => {
    apiMock.getMyCourses.mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(screen.getByRole("heading", { level: 1, name: "我的课程" })).toBeInTheDocument();
    expect(screen.getByTestId("courses-skeleton")).toBeInTheDocument();
  });

  it("正常态：已购课程网格渲染（已购徽章态）+ 数量展示", async () => {
    apiMock.getMyCourses.mockResolvedValue([
      makeCourse(),
      makeCourse({ id: "c-2", title: "Java" }),
    ]);
    renderPage();
    expect(await screen.findByRole("link", { name: /数据结构与算法/ })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Java/ })).toBeInTheDocument();
    expect(screen.getByText("已购 2 门课程")).toBeInTheDocument();
    // CourseCard 已购态：封面「已购」徽章渲染
    expect(screen.getAllByText("已购").length).toBeGreaterThanOrEqual(2);
  });

  it("空态：还没有购买课程 + 引导去课程中心", async () => {
    apiMock.getMyCourses.mockResolvedValue([]);
    renderPage();
    expect(await screen.findByText("还没有购买课程")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "去课程中心看看" })).toHaveAttribute(
      "href",
      "/courses",
    );
  });

  it("错误：通用错误横幅 + 重试闭环恢复", async () => {
    apiMock.getMyCourses
      .mockRejectedValueOnce(new ApiError(503, "服务暂时不可用"))
      .mockResolvedValueOnce([makeCourse()]);
    renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("服务暂时不可用，请稍后重试");
    fireEvent.click(screen.getByRole("button", { name: "重试" }));
    expect(await screen.findByRole("link", { name: /数据结构与算法/ })).toBeInTheDocument();
  });
});

describe("我的课程页：AuthGate 守卫", () => {
  it("静默续期窗口（isLoading）：渲染骨架不渲染业务内容，不弹登录窗", async () => {
    const openLoginDialog = vi.fn();
    authMock.useAuth.mockReturnValue(
      defaultAuth({ user: null, isAuthenticated: false, isLoading: true, openLoginDialog }),
    );
    apiMock.getMyCourses.mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(screen.getByTestId("courses-skeleton")).toBeInTheDocument();
    expect(openLoginDialog).not.toHaveBeenCalled();
  });

  it("续期失败未认证：打开全局登录弹窗兜底", async () => {
    const openLoginDialog = vi.fn();
    authMock.useAuth.mockReturnValue(
      defaultAuth({ user: null, isAuthenticated: false, openLoginDialog }),
    );
    apiMock.getMyCourses.mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(screen.getByTestId("courses-skeleton")).toBeInTheDocument();
    expect(openLoginDialog).toHaveBeenCalledTimes(1);
  });

  it("已认证：直接渲染业务内容（不弹登录窗）", async () => {
    const openLoginDialog = vi.fn();
    authMock.useAuth.mockReturnValue(defaultAuth({ openLoginDialog }));
    apiMock.getMyCourses.mockResolvedValue([makeCourse()]);
    renderPage();
    expect(await screen.findByRole("link", { name: /数据结构与算法/ })).toBeInTheDocument();
    await waitFor(() => {
      expect(openLoginDialog).not.toHaveBeenCalled();
    });
  });
});
