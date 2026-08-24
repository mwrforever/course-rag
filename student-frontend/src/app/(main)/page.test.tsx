/**
 * 首页测试（Task 8 TDD 先行用例）
 *
 * 首页四态全覆盖（设计 §1.7）：Loading 骨架 / Empty 空态 / Error 横幅+重试 / 正常态。
 * 数据层以 vi.mock 注入（react-query 用 QueryClient 包裹并关闭 retry，避免测试噪音）；
 * 覆盖：Hero 问候与 CTA 跳转、Bento 课程网格（n≥3 首卡 2x2、n=2 首卡宽幅 2x1 零空洞、
 * 资料库入口条跨度、单卡退化）、无课程空态、错误横幅重试闭环、最近会话渲染
 * （相对时间 + 继续跳转）、会话空态。
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import HomePage, { librarySpan } from "./page";
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
    expect(screen.getByText("继续探索你的课程")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /开始提问/ })).toHaveAttribute("href", "/chat");
    expect(screen.getByRole("link", { name: "浏览课程" })).toHaveAttribute("href", "/courses");
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

describe("首页四态：我的课程", () => {
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

  it("正常态：Bento 首卡 2x2（封面+标题）与资料库入口条补齐行尾", async () => {
    apiMock.getMyCourses.mockResolvedValue([
      makeCourse({
        id: "c1",
        title: "高等数学（一）",
        coverImage: "http://localhost:9000/b/c1.jpg",
      }),
      makeCourse({ id: "c2", title: "Python 程序设计", category: "计算机" }),
      makeCourse({ id: "c3", title: "大学英语" }),
      makeCourse({ id: "c4", title: "数据结构" }),
      makeCourse({ id: "c5", title: "线性代数", category: "数学" }),
    ]);
    apiMock.getSessions.mockResolvedValue(EMPTY_SESSIONS);
    renderHome();
    await screen.findByText("高等数学（一）");

    // 首卡 2x2：较大封面 + 跳转课程工作台
    const leadLink = screen.getByRole("link", { name: /高等数学（一）/ });
    expect(leadLink).toHaveAttribute("href", "/courses/c1");
    expect(leadLink.parentElement).toHaveClass("md:col-span-2", "md:row-span-2");
    expect(screen.getByAltText("高等数学（一）")).toBeInTheDocument();

    // 无封面课程：按 category 映射低饱和渐变兜底（计算机 → sky，空 → 默认，数学 → violet）
    const fallbacks = screen.getAllByTestId("cover-fallback");
    expect(fallbacks.some((el) => el.classList.contains("from-sky-100"))).toBe(true);
    expect(fallbacks.some((el) => el.classList.contains("from-brand-light"))).toBe(true);
    expect(fallbacks.some((el) => el.classList.contains("from-violet-100"))).toBe(true);

    // 资料库入口条：5 门课程恰好铺满两行 → 入口条整行 4 列（cell 数=课程数+1，不造空 cell）
    const library = screen.getByRole("link", { name: /通用资料库/ });
    expect(library).toHaveAttribute("href", "/courses");
    expect(library.parentElement).toHaveClass("col-span-4");
  });

  it("课程 ≤1：退化单卡居中 + 资料库入口条", async () => {
    apiMock.getMyCourses.mockResolvedValue([makeCourse({ title: "唯一课程" })]);
    apiMock.getSessions.mockResolvedValue(EMPTY_SESSIONS);
    renderHome();
    const card = await screen.findByRole("link", { name: /唯一课程/ });
    expect(card).toHaveAttribute("href", "/courses/c-1");
    // 退化模式：不再出现 2x2 首卡网格布局
    expect(card.parentElement).not.toHaveClass("md:col-span-2");
    expect(screen.getByRole("link", { name: /通用资料库/ })).toBeInTheDocument();
  });

  it("正常态：n=2 首卡降级宽幅 2x1，单行铺满零空洞（不产生 2 个空 cell）", async () => {
    apiMock.getMyCourses.mockResolvedValue([
      makeCourse({ id: "c1", title: "课程甲", coverImage: "http://localhost:9000/b/c1.jpg" }),
      makeCourse({ id: "c2", title: "课程乙" }),
    ]);
    apiMock.getSessions.mockResolvedValue(EMPTY_SESSIONS);
    renderHome();
    await screen.findByText("课程甲");

    // 首卡宽幅 2x1：占行 1 列 1-2，不再 row-span-2（否则 2x2 占满行 2 后行 2 c3-4 空洞）
    const lead = screen.getByRole("link", { name: /课程甲/ });
    expect(lead.parentElement).toHaveClass("col-span-1", "md:col-span-2");
    expect(lead.parentElement).not.toHaveClass("md:row-span-2");
    // 次卡 1x1 落 r1c3 + 入口条 span1 落 r1c4 → 单行铺满
    expect(screen.getByRole("link", { name: /课程乙/ }).parentElement).toHaveClass("col-span-1");
    const library = screen.getByRole("link", { name: /通用资料库/ });
    expect(library.parentElement).toHaveClass("col-span-1");
    // 零空洞验证：Bento 网格恰 3 个 cell（2 课程卡 + 1 入口条），无多余空格占位
    const grid = library.parentElement!.parentElement!;
    expect(grid.className).toContain("md:grid-cols-4");
    expect(grid.children).toHaveLength(3);
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

describe("librarySpan 资料库入口条跨度（不产生空 cell）", () => {
  it("2 门：第 1 行余 1 列（宽幅 2x1 首卡 + 次卡 + 入口条铺满单行）", () => {
    expect(librarySpan(2)).toBe(1);
  });
  it("3 门：第 2 行余 2 列", () => {
    expect(librarySpan(3)).toBe(2);
  });
  it("4 门：第 2 行余 1 列", () => {
    expect(librarySpan(4)).toBe(1);
  });
  it("5 门：恰铺满两行 → 整行 4 列", () => {
    expect(librarySpan(5)).toBe(4);
  });
  it("6 门：第 3 行余 3 列", () => {
    expect(librarySpan(6)).toBe(3);
  });
});
