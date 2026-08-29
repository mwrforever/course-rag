/**
 * 课程列表页测试（Task 9 TDD 先行用例；公开化 2026-08-26 修订）
 *
 * 设计 §1.5.2：本地 category 筛选（Chip 从数据聚合，选中强化）+ 关键词
 * 即时过滤 + 排序（默认评分降序 / 按名称）+ 本地分页（每页 12）+ URL query 浅路由
 * 同步（?category=&q=，关键词写 URL 经 300ms 防抖）。数据源为公开课程接口，
 * 登录用户交叉「我的课程」标记已购徽章（契约 H.2.1）。四态：Loading 骨架 / Empty / Error / 正常态。
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import CoursesPage from "./page";
import type { PublicCourse } from "@/lib/types";

/** 数据层 mock：公开课程源 + 我的课程（已购交叉） */
const apiMock = vi.hoisted(() => ({ getPublicCourses: vi.fn(), getMyCourses: vi.fn() }));
/** 导航 mock：searchParams 读当前 URL（pushState 驱动入口态）；replace 记录浅路由同步 */
const navMock = vi.hoisted(() => ({ replace: vi.fn() }));
/** 认证 mock：默认登录态（joined 徽章用例覆盖开关） */
const authMock = vi.hoisted(() => ({ useAuth: vi.fn() }));

vi.mock("@/lib/api", () => ({
  getPublicCourses: apiMock.getPublicCourses,
  getMyCourses: apiMock.getMyCourses,
}));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authMock.useAuth() }));
vi.mock("next/navigation", () => ({
  useSearchParams: () => new URLSearchParams(window.location.search),
  useRouter: () => ({ replace: navMock.replace }),
}));

/** 构造课程对象（公开 VO 各字段形态可覆盖） */
function makeCourse(overrides: Partial<PublicCourse> = {}): PublicCourse {
  return {
    id: "c-1",
    title: "数据结构与算法",
    description: null,
    coverImage: null,
    category: null,
    instructorName: "王老师",
    duration: "32",
    rating: 4.5,
    learningCount: 256,
    price: 299,
    ...overrides,
  };
}

/** 期望 URL 查询串（与组件内 URLSearchParams 同构编码，避免硬编码中文转义） */
function qs(params: Record<string, string>): string {
  const p = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    p.set(key, value);
  }
  return p.toString();
}

/** 渲染容器：独立 QueryClient（retry 关闭） */
function renderList() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <CoursesPage />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  window.history.pushState({}, "", "/courses");
  navMock.replace.mockReset();
  apiMock.getPublicCourses.mockReset();
  // 已购交叉查询默认空集（已购用例按需覆盖）
  apiMock.getMyCourses.mockReset();
  apiMock.getMyCourses.mockResolvedValue([]);
  authMock.useAuth.mockReset();
  authMock.useAuth.mockReturnValue({
    user: { userId: "u1", role: "STUDENT", displayName: "同学A" },
    accessToken: null,
    isAuthenticated: true,
    isLoading: false,
    login: vi.fn(),
    logout: vi.fn(),
  });
});

afterEach(() => {
  window.history.pushState({}, "", "/courses");
});

describe("课程列表页四态", () => {
  it("Loading：骨架与最终布局同形", async () => {
    apiMock.getPublicCourses.mockReturnValue(new Promise(() => {}));
    renderList();
    expect(screen.getByTestId("courses-skeleton")).toBeInTheDocument();
  });

  it("Error：加载失败 → 横幅「服务暂时不可用」+ 重试闭环恢复", async () => {
    apiMock.getPublicCourses
      .mockRejectedValueOnce(new Error("网络故障"))
      .mockResolvedValueOnce([makeCourse({ id: "c9", title: "恢复后的课程" })]);
    apiMock.getPublicCourses.mockResolvedValue([]);
    renderList();
    const banner = await screen.findByRole("alert");
    expect(banner).toHaveTextContent("服务暂时不可用，请稍后重试");
    fireEvent.click(screen.getByRole("button", { name: "重试" }));
    expect(await screen.findByRole("link", { name: /恢复后的课程/ })).toBeInTheDocument();
  });

  it("Empty：无课程空态（文案 + AI 入口）", async () => {
    apiMock.getPublicCourses.mockResolvedValue([]);
    renderList();
    expect(await screen.findByText("暂无上架课程，请稍后再来")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "先和 AI 助教聊聊" })).toHaveAttribute("href", "/chat");
  });
});

describe("课程列表页筛选/搜索/排序", () => {
  it("默认按评分降序渲染（高分在前）", async () => {
    apiMock.getPublicCourses.mockResolvedValue([
      makeCourse({ id: "c1", title: "低分课程", rating: 2 }),
      makeCourse({ id: "c2", title: "高分课程", rating: 5 }),
      makeCourse({ id: "c3", title: "中分课程", rating: 3 }),
    ]);
    renderList();
    await screen.findByRole("link", { name: /高分课程/ });
    const links = screen.getAllByRole("link");
    const titles = links.map((link) => link.textContent ?? "");
    expect(titles[0]).toContain("高分课程");
    expect(titles[1]).toContain("中分课程");
    expect(titles[2]).toContain("低分课程");
  });

  it("切换名称排序：标题字典序排列", async () => {
    apiMock.getPublicCourses.mockResolvedValue([
      makeCourse({ id: "c1", title: "Beta 课程", rating: 5 }),
      makeCourse({ id: "c2", title: "Alpha 课程", rating: 2 }),
      makeCourse({ id: "c3", title: "Gamma 课程", rating: 3 }),
    ]);
    renderList();
    await screen.findByRole("link", { name: /Beta 课程/ });
    fireEvent.click(screen.getByTestId("sort-option-name"));
    await waitFor(() => {
      const links = screen.getAllByRole("link");
      expect(links[0].textContent).toContain("Alpha 课程");
      expect(links[2].textContent).toContain("Gamma 课程");
    });
  });

  it("category Chip 组从公开数据聚合（去重），点击后仅显示该类课程并浅路由同步", async () => {
    apiMock.getPublicCourses.mockResolvedValue([
      makeCourse({ id: "c1", title: "计算机原理", category: "计算机" }),
      makeCourse({ id: "c2", title: "算法入门", category: "计算机" }),
      makeCourse({ id: "c3", title: "高等数学", category: "数学" }),
      makeCourse({ id: "c4", title: "大学英语", category: "英语" }),
    ]);
    renderList();
    await screen.findByRole("link", { name: /计算机原理/ });
    // Chip 集：全部 + 数据中去重后的分类
    expect(screen.getByRole("tab", { name: "全部" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "计算机" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "数学" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "英语" })).toBeInTheDocument();
    // 点击「数学」：只留数学课程，chips 选中态强化，URL 同步
    fireEvent.click(screen.getByRole("tab", { name: "数学" }));
    expect(await screen.findByRole("link", { name: /高等数学/ }));
    expect(screen.queryByRole("link", { name: /计算机原理/ })).toBeNull();
    // tab 栏选中态：下划线缩放 + 品牌色文字（2026-08-27 tab 化改版）
    expect(screen.getByRole("tab", { name: "数学", selected: true })).toBeInTheDocument();
    expect(screen.getByText("共 1 门课程")).toBeInTheDocument();
    await waitFor(() => {
      expect(navMock.replace).toHaveBeenCalledWith(`/courses?${qs({ category: "数学" })}`);
    });
  });

  it("关键词即时过滤（匹配标题），与 category 组合生效并同步 URL（防抖后写回）", async () => {
    apiMock.getPublicCourses.mockResolvedValue([
      makeCourse({ id: "c1", title: "Java 程序设计", category: "计算机" }),
      makeCourse({ id: "c2", title: "JavaScript 进阶", category: "计算机" }),
      makeCourse({ id: "c3", title: "大学英语", category: "英语" }),
    ]);
    renderList();
    await screen.findByRole("link", { name: /Java 程序设计/ });
    // 先选分类再用关键词收窄：组合过滤（过滤即时，URL 同步 300ms 防抖）
    fireEvent.click(screen.getByRole("tab", { name: "计算机" }));
    const input = screen.getByLabelText("搜索课程");
    fireEvent.change(input, { target: { value: "Script" } });
    await waitFor(() => {
      expect(screen.queryByRole("link", { name: /Java 程序设计/ })).toBeNull();
      expect(screen.getByRole("link", { name: /JavaScript 进阶/ })).toBeInTheDocument();
    });
    await waitFor(() => {
      expect(navMock.replace).toHaveBeenLastCalledWith(
        `/courses?${qs({ category: "计算机", q: "Script" })}`,
      );
    });
  });

  it("筛选无匹配：空态 + 清除筛选恢复全量并回落纯 /courses URL", async () => {
    apiMock.getPublicCourses.mockResolvedValue([
      makeCourse({ id: "c1", title: "计算机原理", category: "计算机" }),
      makeCourse({ id: "c2", title: "高等数学", category: "数学" }),
    ]);
    renderList();
    await screen.findByRole("link", { name: /计算机原理/ });
    fireEvent.change(screen.getByLabelText("搜索课程"), { target: { value: "不存在的关键词" } });
    expect(await screen.findByText("没有找到相关课程，换个关键词或分类试试")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "清除筛选" }));
    expect(await screen.findByRole("link", { name: /计算机原理/ })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /高等数学/ })).toBeInTheDocument();
    await waitFor(() => {
      expect(navMock.replace).toHaveBeenLastCalledWith("/courses");
    });
  });

  it("登录用户：我的课程交叉 → 已购徽章（未购卡片展示价格）", async () => {
    apiMock.getPublicCourses.mockResolvedValue([
      makeCourse({ id: "c1", title: "已购课程", category: "计算机" }),
      makeCourse({ id: "c2", title: "未购课程", category: "计算机" }),
    ]);
    apiMock.getMyCourses.mockResolvedValue([{ id: "c1" }] as never);
    renderList();
    await screen.findByText("已购课程");
    // 仅 c1 标记「已购」（c2 无徽章且展示价格行，契约 H.2.1）
    expect(screen.getAllByText("已购")).toHaveLength(1);
    expect(screen.getByText("¥299")).toBeInTheDocument();
  });
});

describe("课程列表页 URL query 驱动与本地分页", () => {
  it("URL 入口（?category=&q=）驱动初始过滤态", async () => {
    window.history.pushState({}, "", `/courses?${qs({ category: "数学", q: "高等" })}`);
    apiMock.getPublicCourses.mockResolvedValue([
      makeCourse({ id: "c1", title: "高等数学", category: "数学" }),
      makeCourse({ id: "c2", title: "离散数学", category: "数学" }),
      makeCourse({ id: "c3", title: "计算机原理", category: "计算机" }),
    ]);
    renderList();
    expect(await screen.findByRole("link", { name: /高等数学/ })).toBeInTheDocument();
    // 关键词 + 分类同时收窄：离散数学被关键词排除，计算机被分类排除
    expect(screen.queryByRole("link", { name: /离散数学/ })).toBeNull();
    expect(screen.queryByRole("link", { name: /计算机原理/ })).toBeNull();
  });

  it("分页：13 门课每页 12，翻页后展示末条并禁用边界按钮", async () => {
    const courses = Array.from({ length: 13 }, (_, index) =>
      makeCourse({ id: `c${index + 1}`, title: `测试课程 ${String(index + 1).padStart(2, "0")}` }),
    );
    apiMock.getPublicCourses.mockResolvedValue(courses);
    renderList();
    // 首页：第 1 门在、第 13 门不在；上一页禁用（链接名含 meta 文本，用正则匹配标题）
    await screen.findByRole("link", { name: /测试课程 01/ });
    expect(screen.getByRole("link", { name: /测试课程 12/ })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /测试课程 13/ })).toBeNull();
    expect(screen.getByText("第 1 / 2 页")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "上一页" })).toBeDisabled();
    // 翻页：末条出现，首页条目移除，下一页禁用
    fireEvent.click(screen.getByRole("button", { name: "下一页" }));
    expect(await screen.findByRole("link", { name: /测试课程 13/ })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /测试课程 01/ })).toBeNull();
    expect(screen.getByText("第 2 / 2 页")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "下一页" })).toBeDisabled();
  });
});
