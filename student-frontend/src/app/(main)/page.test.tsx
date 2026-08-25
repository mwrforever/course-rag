/**
 * 首页测试（UI 重构 2026-08-25：电商风首页）
 *
 * 首页四态全覆盖（设计 §1.7）：Loading 骨架 / Empty 空态 / Error 横幅+重试 / 正常态。
 * 新增电商语义覆盖：分类筛选条（全部 + 各分类计数，点击过滤）、课程网格、
 * 资料库入口横幅；旧 Bento 零空洞布局（librarySpan）已随重构移除，对应用例删除。
 * 数据层以 vi.mock 注入（react-query 用 QueryClient 包裹并关闭 retry）。
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import HomePage from "./page";
import type { SessionItem, StudentCourse } from "@/lib/types";

/** 数据层 mock：getMyCourses / getSessions 返回值按用例注入（成功/失败/挂起） */
const apiMock = vi.hoisted(() => ({ getMyCourses: vi.fn(), getSessions: vi.fn() }));
/** 认证 mock：displayName 可切换（Hero 问候语断言） */
const authMock = vi.hoisted(() => ({ useAuth: vi.fn() }));

vi.mock("@/lib/api", () => ({
  getMyCourses: apiMock.getMyCourses,
  getSessions: apiMock.getSessions,
}));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authMock.useAuth() }));

/** 空会话分页响应（J6：total 为 Long→string） */
const EMPTY_SESSIONS = { records: [], total: "0", page: 1, size: 5 };

/** 构造课程对象（J1 各字段形态可覆盖） */
function makeCourse(overrides: Partial<StudentCourse> = {}): StudentCourse {
  return {
    id: "c-1",
    title: "数据结构与算法",
    coverImage: null,
    category: null,
    instructorName: "王老师",
    duration: "32",
    rating: 4.5,
    learningCount: 256,
    ...overrides,
  };
}

/** 构造会话对象（默认 5 分钟前创建，可覆盖为最近时间） */
function makeSession(overrides: Partial<SessionItem> = {}): SessionItem {
  return {
    id: "s-1",
    title: "什么是索引下推",
    status: "ACTIVE",
    createdAt: new Date(Date.now() - 5 * 60_000).toISOString(),
    lastMessageAt: null,
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

beforeEach(() => {
  apiMock.getMyCourses.mockReset();
  apiMock.getSessions.mockReset();
  authMock.useAuth.mockReset();
  // 默认登录态：同学A
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
  apiMock.getMyCourses.mockReset();
  apiMock.getSessions.mockReset();
  authMock.useAuth.mockReset();
});

describe("首页 Hero", () => {
  it("问候 displayName + 主 CTA 跳转（/chat 与 /courses）", async () => {
    apiMock.getMyCourses.mockResolvedValue([]);
    apiMock.getSessions.mockResolvedValue(EMPTY_SESSIONS);
    renderHome();
    expect(await screen.findByText("你好，同学A")).toBeInTheDocument();
    expect(screen.getByText("课堂资料、AI 助教、对话溯源，都在一个地方")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /开始提问/ })).toHaveAttribute("href", "/chat");
    expect(screen.getByRole("link", { name: "浏览课堂" })).toHaveAttribute("href", "/courses");
    // AI 助教人格化徽标驻留 Hero 右栏
    expect(screen.getByTestId("ai-badge")).toBeInTheDocument();
  });

  it("未登录（displayName 缺失）问候回退「同学」", async () => {
    authMock.useAuth.mockReturnValue({
      user: null,
      accessToken: null,
      isAuthenticated: false,
      isLoading: false,
      login: vi.fn(),
      logout: vi.fn(),
    });
    apiMock.getMyCourses.mockResolvedValue([]);
    apiMock.getSessions.mockResolvedValue(EMPTY_SESSIONS);
    renderHome();
    expect(await screen.findByText("你好，同学")).toBeInTheDocument();
  });
});

describe("首页四态：推荐课程", () => {
  it("Loading：骨架与最终布局同形（课程 + 会话骨架就位）", async () => {
    // 挂起 promise：查询保持 pending，骨架持久可见
    apiMock.getMyCourses.mockReturnValue(new Promise(() => {}));
    apiMock.getSessions.mockReturnValue(new Promise(() => {}));
    renderHome();
    expect(screen.getByTestId("courses-skeleton")).toBeInTheDocument();
    expect(screen.getByTestId("sessions-skeleton")).toBeInTheDocument();
  });

  it("Empty：无课程空态（文案 + 行动入口 + AI 徽标）", async () => {
    apiMock.getMyCourses.mockResolvedValue([]);
    apiMock.getSessions.mockResolvedValue(EMPTY_SESSIONS);
    renderHome();
    expect(await screen.findByText("还没有加入课程，请联系老师开通")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "先和 AI 助教聊聊" })).toHaveAttribute("href", "/chat");
  });

  it("Error：加载失败 → 横幅「服务暂时不可用」+ 重试闭环恢复", async () => {
    apiMock.getMyCourses
      .mockRejectedValueOnce(new Error("网络故障"))
      .mockResolvedValueOnce([makeCourse({ id: "c9", title: "恢复后的课程" })]);
    apiMock.getSessions.mockResolvedValue(EMPTY_SESSIONS);
    renderHome();
    const banner = await screen.findByRole("alert");
    expect(banner).toHaveTextContent("服务暂时不可用，请稍后重试");
    fireEvent.click(screen.getByRole("button", { name: "重试" }));
    expect(await screen.findByText("恢复后的课程")).toBeInTheDocument();
  });

  it("正常态：电商卡片网格 + 分类筛选条（各分类计数、点击过滤、全部复位）", async () => {
    apiMock.getMyCourses.mockResolvedValue([
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
    apiMock.getSessions.mockResolvedValue(EMPTY_SESSIONS);
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
    expect(fallbacks.some((el) => el.classList.contains("from-sky-100"))).toBe(true);
    expect(fallbacks.some((el) => el.classList.contains("from-violet-100"))).toBe(true);
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

  it("课程 ≤1：单卡仍渲染 + 资料库入口横幅", async () => {
    apiMock.getMyCourses.mockResolvedValue([makeCourse({ title: "唯一课程" })]);
    apiMock.getSessions.mockResolvedValue(EMPTY_SESSIONS);
    renderHome();
    const card = await screen.findByRole("link", { name: /唯一课程/ });
    expect(card).toHaveAttribute("href", "/courses/c-1");
    expect(screen.getByRole("link", { name: /通用资料库/ })).toBeInTheDocument();
  });
});

describe("首页：最近会话", () => {
  it("渲染会话条目：标题 + 相对时间 + 跳转继续", async () => {
    apiMock.getMyCourses.mockResolvedValue([makeCourse({})]);
    apiMock.getSessions.mockResolvedValue({
      records: [
        makeSession({
          id: "s1",
          title: "什么叫索引下推",
          lastMessageAt: new Date(Date.now() - 2 * 60_000).toISOString(),
        }),
        makeSession({ id: "s2", title: "RAG 是什么" }),
      ],
      total: "2",
      page: 1,
      size: 5,
    });
    renderHome();
    const recent = await screen.findByRole("link", { name: /什么叫索引下推/ });
    expect(recent).toHaveAttribute("href", "/chat/s1");
    expect(recent).toHaveTextContent("2 分钟前");
    expect(recent).toHaveTextContent("继续");
    const second = screen.getByRole("link", { name: /RAG 是什么/ });
    expect(second).toHaveAttribute("href", "/chat/s2");
    // 无 lastMessageAt 时回退 createdAt（5 分钟前）
    expect(second).toHaveTextContent("5 分钟前");
  });

  it("Empty：还没有会话记录 + 「开始对话」入口", async () => {
    apiMock.getMyCourses.mockResolvedValue([makeCourse({})]);
    apiMock.getSessions.mockResolvedValue(EMPTY_SESSIONS);
    renderHome();
    expect(await screen.findByText(/还没有会话记录/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "开始对话" })).toHaveAttribute("href", "/chat");
  });

  it("Error：会话加载失败 → 横幅 + 重试恢复", async () => {
    apiMock.getMyCourses.mockResolvedValue([makeCourse({})]);
    apiMock.getSessions.mockRejectedValueOnce(new Error("网络故障")).mockResolvedValueOnce({
      records: [makeSession({ id: "s9", title: "恢复的会话" })],
      total: "1",
      page: 1,
      size: 5,
    });
    renderHome();
    const banner = await screen.findByRole("alert");
    expect(banner).toHaveTextContent("服务暂时不可用，请稍后重试");
    fireEvent.click(screen.getByRole("button", { name: "重试" }));
    expect(await screen.findByRole("link", { name: /恢复的会话/ })).toBeInTheDocument();
  });
});
