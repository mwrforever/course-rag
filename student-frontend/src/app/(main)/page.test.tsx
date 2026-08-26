/**
 * 首页测试（公开化 2026-08-26：公开课程源 + 快问框 + 登录弹窗触发）
 *
 * 首页四态全覆盖（设计 §1.7）：Loading 骨架 / Empty 空态 / Error 横幅+重试 / 正常态。
 * 新增公开化语义覆盖：快问框（登录/未登录提交分派）、推荐课程（公开接口数据源 +
 * 已加入徽章交叉）、?login=1 触发登录弹窗；最近会话区块已随会话管理归侧边栏移除。
 * 数据层以 vi.mock 注入（react-query 用 QueryClient 包裹并关闭 retry）。
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import HomePage from "./page";
import type { PublicCourse } from "@/lib/types";

/** 数据层 mock：getPublicCourses（公开源）/ getMyCourses（已加入交叉） */
const apiMock = vi.hoisted(() => ({ getPublicCourses: vi.fn(), getMyCourses: vi.fn() }));
/** 认证 mock：登录态可切换 + 弹窗操作记录 */
const authMock = vi.hoisted(() => ({ useAuth: vi.fn() }));
/** 路由 mock：push/replace 记录（快问提交与 login 参数清理） */
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
    category: null,
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
  authMock.useAuth.mockReturnValue(defaultAuth());
  // 默认无 login 参数（history 残留清理）
  window.history.replaceState(null, "", "/");
});

afterEach(() => {
  apiMock.getPublicCourses.mockReset();
  apiMock.getMyCourses.mockReset();
  authMock.useAuth.mockReset();
});

describe("首页 Hero", () => {
  it("问候 displayName + 快问框 + CTA 跳转（/chat 与 /courses）", async () => {
    apiMock.getPublicCourses.mockResolvedValue([]);
    apiMock.getMyCourses.mockResolvedValue([]);
    renderHome();
    expect(await screen.findByText("你好，同学A")).toBeInTheDocument();
    expect(screen.getByText("课堂资料、AI 助教、对话溯源，都在一个地方")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "提问" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /开始提问/ })).toHaveAttribute("href", "/chat");
    expect(screen.getByRole("link", { name: "浏览课堂" })).toHaveAttribute("href", "/courses");
    // AI 助教人格化徽标驻留 Hero 右栏
    expect(screen.getByTestId("ai-badge")).toBeInTheDocument();
  });

  it("未登录（displayName 缺失）问候回退「同学」", async () => {
    authMock.useAuth.mockReturnValue(defaultAuth({ user: null, isAuthenticated: false }));
    apiMock.getPublicCourses.mockResolvedValue([]);
    apiMock.getMyCourses.mockResolvedValue([]);
    renderHome();
    expect(await screen.findByText("你好，同学")).toBeInTheDocument();
  });
});

describe("首页：快速提问", () => {
  it("已登录提交：跳转 /chat?q= 预填问题", async () => {
    apiMock.getPublicCourses.mockResolvedValue([]);
    apiMock.getMyCourses.mockResolvedValue([]);
    renderHome();
    const input = await screen.findByLabelText("快速提问");
    fireEvent.change(input, { target: { value: "什么是索引下推" } });
    fireEvent.click(screen.getByRole("button", { name: "提问" }));
    expect(routerMock.push).toHaveBeenCalledWith(
      "/chat?q=%E4%BB%80%E4%B9%88%E6%98%AF%E7%B4%A2%E5%BC%95%E4%B8%8B%E6%8E%A8",
    );
  });

  it("未登录提交：先开登录弹窗（afterLogin 登录成功后继续跳转）", async () => {
    const openLoginDialog = vi.fn();
    authMock.useAuth.mockReturnValue(
      defaultAuth({ user: null, isAuthenticated: false, openLoginDialog }),
    );
    apiMock.getPublicCourses.mockResolvedValue([]);
    apiMock.getMyCourses.mockResolvedValue([]);
    renderHome();
    const input = await screen.findByLabelText("快速提问");
    fireEvent.change(input, { target: { value: "什么是 RAG" } });
    fireEvent.click(screen.getByRole("button", { name: "提问" }));
    expect(openLoginDialog).toHaveBeenCalledTimes(1);
    const options = openLoginDialog.mock.calls[0][0];
    expect(typeof options.afterLogin).toBe("function");
    // 未登录时不直接跳转（登录完成后经 afterLogin 跳）
    expect(routerMock.push).not.toHaveBeenCalled();
    // afterLogin 执行后跳转课程助手
    options.afterLogin();
    expect(routerMock.push).toHaveBeenCalledWith("/chat?q=%E4%BB%80%E4%B9%88%E6%98%AF%20RAG");
  });

  it("空输入提交：不跳转也不弹窗", async () => {
    const openLoginDialog = vi.fn();
    authMock.useAuth.mockReturnValue(defaultAuth({ openLoginDialog }));
    apiMock.getPublicCourses.mockResolvedValue([]);
    apiMock.getMyCourses.mockResolvedValue([]);
    renderHome();
    fireEvent.click(await screen.findByRole("button", { name: "提问" }));
    expect(routerMock.push).not.toHaveBeenCalled();
    expect(openLoginDialog).not.toHaveBeenCalled();
  });
});

describe("首页四态：推荐课程", () => {
  it("Loading：骨架与最终布局同形", async () => {
    // 挂起 promise：查询保持 pending，骨架持久可见
    apiMock.getPublicCourses.mockReturnValue(new Promise(() => {}));
    apiMock.getMyCourses.mockResolvedValue([]);
    renderHome();
    expect(screen.getByTestId("courses-skeleton")).toBeInTheDocument();
  });

  it("Empty：无课程空态（文案 + 行动入口）", async () => {
    apiMock.getPublicCourses.mockResolvedValue([]);
    apiMock.getMyCourses.mockResolvedValue([]);
    renderHome();
    expect(await screen.findByText("暂无上架课程，请稍后再来")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "先和 AI 助教聊聊" })).toHaveAttribute("href", "/chat");
  });

  it("Error：加载失败 → 横幅「服务暂时不可用」+ 重试闭环恢复", async () => {
    apiMock.getPublicCourses
      .mockRejectedValueOnce(new Error("网络故障"))
      .mockResolvedValueOnce([makeCourse({ id: "c9", title: "恢复后的课程" })]);
    apiMock.getMyCourses.mockResolvedValue([]);
    renderHome();
    const banner = await screen.findByRole("alert");
    expect(banner).toHaveTextContent("服务暂时不可用，请稍后重试");
    fireEvent.click(screen.getByRole("button", { name: "重试" }));
    expect(await screen.findByText("恢复后的课程")).toBeInTheDocument();
  });

  it("正常态：公开课程网格 + 分类筛选条（各分类计数、点击过滤、全部复位）", async () => {
    apiMock.getPublicCourses.mockResolvedValue([
      makeCourse({
        id: "c1",
        title: "高等数学（一）",
        coverImage: "http://localhost:9000/b/c1.jpg",
      }),
      makeCourse({ id: "c2", title: "Python 程序设计", category: "计算机" }),
      makeCourse({ id: "c3", title: "Web 前端开发", category: "计算机" }),
      makeCourse({ id: "c4", title: "线性代数", category: "数学" }),
      makeCourse({ id: "c5", title: "数据结构", category: null }),
    ]);
    apiMock.getMyCourses.mockResolvedValue([{ id: "c2" }]);
    renderHome();
    // 首卡跳转课程工作台 + 封面
    await screen.findByText("高等数学（一）");
    const leadLink = screen.getByRole("link", { name: /高等数学（一）/ });
    expect(leadLink).toHaveAttribute("href", "/courses/c1");
    expect(screen.getByAltText("高等数学（一）")).toBeInTheDocument();

    // 分类筛选条：全部(5) + 未分类(2) + 计算机(2) + 数学(1)（Map 按课程数据出现序聚合）
    const chips = screen.getAllByTestId("category-chip");
    expect(chips.map((chip) => chip.textContent?.replace(/\s/g, ""))).toEqual([
      "全部5",
      "未分类2",
      "计算机2",
      "数学1",
    ]);
    // 默认「全部」选中：5 门课程全部渲染（无封面课程按分类映射渐变兜底）
    const fallbacks = screen.getAllByTestId("cover-fallback");
    expect(fallbacks.some((el) => el.classList.contains("from-subject-code-start"))).toBe(true);
    expect(fallbacks.some((el) => el.classList.contains("from-subject-math-start"))).toBe(true);
    expect(fallbacks.some((el) => el.classList.contains("from-brand-light"))).toBe(true);

    // 点计算机：过滤到 2 门，Python/Web 仍在、高等数学消失（aria-pressed 反映选中态）
    fireEvent.click(screen.getByRole("button", { name: /计算机/ }));
    expect(await screen.findByText("Python 程序设计")).toBeInTheDocument();
    expect(screen.getByText("Web 前端开发")).toBeInTheDocument();
    expect(screen.queryByText("高等数学（一）")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /计算机/ })).toHaveAttribute("aria-pressed", "true");

    // 点全部：恢复全部
    fireEvent.click(screen.getByRole("button", { name: /全部/ }));
    expect(await screen.findByText("高等数学（一）")).toBeInTheDocument();

    // 资料库入口横幅：跳转课程列表
    const library = screen.getByRole("link", { name: /通用资料库/ });
    expect(library).toHaveAttribute("href", "/courses");
  });

  it("登录用户：我的课程交叉 → 已加入徽章（only 已加入课程）", async () => {
    apiMock.getPublicCourses.mockResolvedValue([
      makeCourse({ id: "c1", title: "已加入课程" }),
      makeCourse({ id: "c2", title: "未加入课程" }),
    ]);
    apiMock.getMyCourses.mockResolvedValue([{ id: "c1" }]);
    renderHome();
    await screen.findByText("已加入课程");
    const joinedBadges = screen.getAllByText("已加入");
    expect(joinedBadges).toHaveLength(1);
    expect(screen.getByText("已加入")).toBeInTheDocument();
  });

  it("课程 ≤1：单卡仍渲染 + 资料库入口横幅", async () => {
    apiMock.getPublicCourses.mockResolvedValue([makeCourse({ title: "唯一课程" })]);
    apiMock.getMyCourses.mockResolvedValue([]);
    renderHome();
    const card = await screen.findByRole("link", { name: /唯一课程/ });
    expect(card).toHaveAttribute("href", "/courses/c-1");
    expect(screen.getByRole("link", { name: /通用资料库/ })).toBeInTheDocument();
  });
});

describe("首页：登录弹窗触发", () => {
  it("URL 带 ?login=1（middleware 携带）→ 自动打开登录弹窗并清参", async () => {
    const openLoginDialog = vi.fn();
    authMock.useAuth.mockReturnValue(
      defaultAuth({ user: null, isAuthenticated: false, openLoginDialog }),
    );
    apiMock.getPublicCourses.mockResolvedValue([]);
    apiMock.getMyCourses.mockResolvedValue([]);
    window.history.replaceState(null, "", "/?login=1");
    renderHome();
    await screen.findByText("你好，同学");
    expect(openLoginDialog).toHaveBeenCalledTimes(1);
    expect(routerMock.replace).toHaveBeenCalledWith("/", { scroll: false });
  });
});
