/**
 * 课程工作台测试（Task 9 TDD 先行用例；公开化 2026-08-26 修订）
 *
 * 设计 §1.5.3 + 登录门槛（用户拍板：点进详情页才需要登录）：
 * - 课程公开信息经公开接口渲染（未登录可浏览）；未登录自动弹登录窗 + 资料区登录墙
 * - 资料分片列表（面包屑/3 行截断/页码 badge/查看上下文）仅登录后加载
 * - 未选课 403 专属引导页 + 分批渲染（首屏 50 + 加载更多，G10）+ 上下文抽屉（J4）
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import CourseWorkbenchPage from "./page";
import { ApiError } from "@/lib/api";
import type { ChunkContext, MaterialChunk, PublicCourse } from "@/lib/types";

/** 数据层 mock：公开课程 / J2 资料 / J4 上下文按用例注入 */
const apiMock = vi.hoisted(() => ({
  getPublicCourses: vi.fn(),
  getMaterials: vi.fn(),
  getChunkContext: vi.fn(),
}));
/** 认证 mock：登录态可切换 + 弹窗操作记录 */
const authMock = vi.hoisted(() => ({ useAuth: vi.fn() }));
/** 路由 mock：/courses/[id] 动态段 + router.push（问 AI 助教改为按钮文案后跳转） */
const navMock = vi.hoisted(() => ({ params: { id: "c-1" }, push: vi.fn() }));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    getPublicCourses: apiMock.getPublicCourses,
    getMaterials: apiMock.getMaterials,
    getChunkContext: apiMock.getChunkContext,
  };
});
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authMock.useAuth() }));
vi.mock("next/navigation", () => ({
  useParams: () => navMock.params,
  useRouter: () => ({ push: navMock.push }),
}));

/** 默认认证返回值：已登录（未登录用例覆盖开关） */
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

/** 课程对象（公开 VO 形态，id 与路由段对齐） */
function makeCourse(overrides: Partial<PublicCourse> = {}): PublicCourse {
  return {
    id: "c-1",
    title: "数据结构与算法",
    description: "公开的课程简介",
    coverImage: "http://localhost:9000/b/c1.jpg",
    category: "计算机",
    instructorName: "王老师",
    duration: "32",
    rating: 4.5,
    learningCount: 256,
    ...overrides,
  };
}

/** 资料分片对象（J2 形态） */
function makeChunk(index: number, overrides: Partial<MaterialChunk> = {}): MaterialChunk {
  return {
    id: `chunk-${index}`,
    content: `分片正文 ${String(index).padStart(2, "0")}：算法复杂度分析核心内容。`,
    headingPath: "第一章 > 1.1 复杂度分析",
    chunkIndex: index,
    parentTitle: "第一章 引言",
    startPage: 3,
    endPage: 5,
    ...overrides,
  };
}

/** J4 上下文 fixture（父/前/后齐备） */
const CONTEXT: ChunkContext = {
  id: "chunk-1",
  docId: "doc-1",
  kbId: "kb-1",
  content: "当前分片内容全文",
  headingPath: "第一章 > 1.1 复杂度分析",
  chunkIndex: 1,
  courseId: "c-1",
  parentChunkId: "parent-1",
  prevChunkId: null,
  nextChunkId: "chunk-2",
  parent: {
    id: "parent-1",
    content: "父章节聚合内容",
    headingPath: "第一章",
    chunkIndex: 1,
    parentTitle: "第一章 引言",
  },
  prev: null,
  next: {
    id: "chunk-2",
    content: "下一分片内容",
    headingPath: "第一章 > 1.2 实现细节",
    chunkIndex: 2,
    parentTitle: "第一章 引言",
  },
};

/** 渲染容器：独立 QueryClient（retry 关闭） */
function renderWorkbench() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <CourseWorkbenchPage />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  apiMock.getPublicCourses.mockReset();
  apiMock.getMaterials.mockReset();
  apiMock.getChunkContext.mockReset();
  navMock.push.mockReset();
  authMock.useAuth.mockReset();
  authMock.useAuth.mockReturnValue(defaultAuth());
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("课程工作台：加载与四态", () => {
  it("Loading：Hero 骨架 + 资料列表骨架", async () => {
    apiMock.getPublicCourses.mockReturnValue(new Promise(() => {}));
    apiMock.getMaterials.mockResolvedValue([]);
    renderWorkbench();
    expect(screen.getByTestId("workbench-skeleton")).toBeInTheDocument();
  });

  it("Error：资料加载失败（已登录）→ 横幅 + 重试闭环恢复", async () => {
    apiMock.getPublicCourses.mockResolvedValue([makeCourse()]);
    apiMock.getMaterials
      .mockRejectedValueOnce(new ApiError(503, "服务暂时不可用"))
      .mockResolvedValueOnce([makeChunk(1)]);
    renderWorkbench();
    expect(await screen.findByRole("alert")).toHaveTextContent("服务暂时不可用，请稍后重试");
    fireEvent.click(screen.getByRole("button", { name: "重试" }));
    expect(await screen.findByText(/分片正文 01/)).toBeInTheDocument();
  });

  it("公开源无此 id：空态 + 返回课程中心", async () => {
    apiMock.getPublicCourses.mockResolvedValue([makeCourse({ id: "c-other" })]);
    apiMock.getMaterials.mockResolvedValue([]);
    renderWorkbench();
    expect(await screen.findByText("课程不存在或已下架")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "返回课程中心" })).toHaveAttribute("href", "/courses");
  });
});

describe("课程工作台：未登录登录门槛", () => {
  it("未登录进入自动弹登录窗，公开信息可浏览 + 资料区登录墙", async () => {
    const openLoginDialog = vi.fn();
    authMock.useAuth.mockReturnValue(
      defaultAuth({ user: null, isAuthenticated: false, openLoginDialog }),
    );
    apiMock.getPublicCourses.mockResolvedValue([makeCourse()]);
    apiMock.getMaterials.mockResolvedValue([]);
    renderWorkbench();
    // 自动弹窗一次（可关闭继续浏览）
    expect(openLoginDialog).toHaveBeenCalledTimes(1);
    // 公开课程信息可浏览（Hero + 简介）
    expect(
      await screen.findByRole("heading", { level: 1, name: "数据结构与算法" }),
    ).toBeInTheDocument();
    expect(screen.getByText("公开的课程简介")).toBeInTheDocument();
    // 资料区为登录墙（不请求资料接口）
    expect(screen.getByTestId("login-gate")).toBeInTheDocument();
    expect(apiMock.getMaterials).not.toHaveBeenCalled();
    // 点击「去登录」再次打开弹窗
    fireEvent.click(screen.getByRole("button", { name: "去登录" }));
    expect(openLoginDialog).toHaveBeenCalledTimes(2);
  });

  it("未登录点「问 AI 助教」：先弹登录窗（afterLogin 登录成功后继续跳转）", async () => {
    const openLoginDialog = vi.fn();
    authMock.useAuth.mockReturnValue(
      defaultAuth({ user: null, isAuthenticated: false, openLoginDialog }),
    );
    apiMock.getPublicCourses.mockResolvedValue([makeCourse()]);
    apiMock.getMaterials.mockResolvedValue([]);
    renderWorkbench();
    await screen.findByRole("heading", { level: 1, name: "数据结构与算法" });
    fireEvent.click(screen.getByRole("button", { name: /问 AI 助教/ }));
    expect(openLoginDialog).toHaveBeenCalled();
    const options = openLoginDialog.mock.calls.at(-1)?.[0];
    expect(typeof options.afterLogin).toBe("function");
    // 未登录时不直接跳转
    expect(navMock.push).not.toHaveBeenCalled();
    // afterLogin 执行后按 D7 契约跳转（courseId + 课程名）
    options.afterLogin();
    expect(navMock.push).toHaveBeenCalledWith(
      "/chat?courseId=c-1&course=%E6%95%B0%E6%8D%AE%E7%BB%93%E6%9E%84%E4%B8%8E%E7%AE%97%E6%B3%95",
    );
  });

  it("静默续期窗口（loading）内不弹窗：登录用户 refresh 完成前不误弹（once 语义）", async () => {
    const openLoginDialog = vi.fn();
    // 首渲染：refresh 进行中（isLoading）→ 不弹；续期完成登录 → 不误弹
    authMock.useAuth.mockReturnValue(
      defaultAuth({ isLoading: true, isAuthenticated: false, openLoginDialog }),
    );
    apiMock.getPublicCourses.mockResolvedValue([makeCourse()]);
    apiMock.getMaterials.mockResolvedValue([]);
    const view = renderWorkbench();
    expect(openLoginDialog).not.toHaveBeenCalled();
    // 续期成功 → 登录态建立（组件的 login gate 不重复触发）
    authMock.useAuth.mockReturnValue(defaultAuth({ isAuthenticated: true, openLoginDialog }));
    view.rerender(
      <QueryClientProvider
        client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
      >
        <CourseWorkbenchPage />
      </QueryClientProvider>,
    );
    expect(openLoginDialog).not.toHaveBeenCalled();
  });
});

describe("课程工作台：未选课 403 专属引导页（已登录）", () => {
  it("materials 403 → 渲染「联系老师加入这门课程」引导 + 返回按钮，不渲染课程内容", async () => {
    apiMock.getPublicCourses.mockResolvedValue([makeCourse()]);
    apiMock.getMaterials.mockRejectedValue(new ApiError(403, "未选此课程，无权查看资料"));
    renderWorkbench();
    expect(await screen.findByText("还没有加入这门课程，请联系老师开通")).toBeInTheDocument();
    const back = screen.getByRole("link", { name: "返回课程中心" });
    expect(back).toHaveAttribute("href", "/courses");
    // 专属引导态：课程 Hero 与资料列表均不渲染
    expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
    expect(screen.queryByText("课程资料")).toBeNull();
  });

  it("非 403 错误不落入引导页（走通用 Error 横幅）", async () => {
    apiMock.getPublicCourses.mockResolvedValue([makeCourse()]);
    apiMock.getMaterials.mockRejectedValue(new ApiError(500, "内部错误"));
    renderWorkbench();
    expect(await screen.findByRole("alert")).toHaveTextContent("服务暂时不可用，请稍后重试");
    expect(screen.queryByText("还没有加入这门课程，请联系老师开通")).toBeNull();
  });
});

describe("课程工作台：Hero 与资料列表（已登录）", () => {
  it("Hero：封面 + 标题 + 简介 + 讲师/课时/评分/人数 + category 徽章 + 双 CTA", async () => {
    apiMock.getPublicCourses.mockResolvedValue([makeCourse()]);
    apiMock.getMaterials.mockResolvedValue([]);
    renderWorkbench();
    expect(
      await screen.findByRole("heading", { level: 1, name: "数据结构与算法" }),
    ).toBeInTheDocument();
    expect(screen.getByAltText("数据结构与算法")).toBeInTheDocument();
    expect(screen.getByText("公开的课程简介")).toBeInTheDocument();
    expect(screen.getByText("王老师")).toBeInTheDocument();
    expect(screen.getByText("32 课时")).toBeInTheDocument();
    expect(screen.getByText("4.5")).toBeInTheDocument();
    expect(screen.getByText("256 人学习")).toBeInTheDocument();
    expect(screen.getByText("计算机")).toBeInTheDocument();
    // 问 AI 助教：已登录按钮点击后经 router 跳转（D7 上下文面包屑，carry3）
    fireEvent.click(screen.getByRole("button", { name: /问 AI 助教/ }));
    expect(navMock.push).toHaveBeenCalledWith(
      "/chat?courseId=c-1&course=%E6%95%B0%E6%8D%AE%E7%BB%93%E6%9E%84%E4%B8%8E%E7%AE%97%E6%B3%95",
    );
    expect(screen.getByRole("link", { name: /浏览资料/ })).toHaveAttribute("href", "#materials");
  });

  it("无封面时 Hero 走学科渐变兜底", async () => {
    apiMock.getPublicCourses.mockResolvedValue([makeCourse({ coverImage: null })]);
    apiMock.getMaterials.mockResolvedValue([]);
    renderWorkbench();
    await screen.findByRole("heading", { level: 1, name: "数据结构与算法" });
    expect(screen.getByTestId("hero-cover-fallback")).toBeInTheDocument();
  });

  it("资料项渲染：面包屑、3 行截断内容、页码区间/单页 badge、null 页码省略", async () => {
    apiMock.getPublicCourses.mockResolvedValue([makeCourse()]);
    apiMock.getMaterials.mockResolvedValue([
      makeChunk(1),
      makeChunk(2, { startPage: 7, endPage: 7 }),
      makeChunk(3, { startPage: null, endPage: null }),
    ]);
    renderWorkbench();
    expect(await screen.findByText(/分片正文 01/)).toBeInTheDocument();
    const breadcrumbs = screen.getAllByTestId("chunk-breadcrumb");
    expect(breadcrumbs).toHaveLength(3);
    expect(breadcrumbs[0]).toHaveTextContent("第一章");
    expect(screen.getByText("第 3-5 页")).toBeInTheDocument();
    expect(screen.getByText("第 7 页")).toBeInTheDocument();
    expect(screen.getByText("共 3 条")).toBeInTheDocument();
  });

  it("空资料：展示空态文案", async () => {
    apiMock.getPublicCourses.mockResolvedValue([makeCourse()]);
    apiMock.getMaterials.mockResolvedValue([]);
    renderWorkbench();
    expect(await screen.findByText("这门课程还没有资料，稍后再来看看")).toBeInTheDocument();
  });
});

describe("课程工作台：分批渲染与上下文抽屉（已登录）", () => {
  it("分批渲染：首屏 50 条 + 「加载更多（剩余 N 条）」逐批揭示", async () => {
    apiMock.getPublicCourses.mockResolvedValue([makeCourse()]);
    const chunks = Array.from({ length: 55 }, (_, index) => makeChunk(index + 1));
    apiMock.getMaterials.mockResolvedValue(chunks);
    renderWorkbench();
    // 首屏 50：第 50 条在，第 51 条未渲染
    expect(await screen.findByText(/分片正文 50/)).toBeInTheDocument();
    expect(screen.queryByText(/分片正文 51/)).toBeNull();
    // 加载更多按钮提示剩余条数
    const more = screen.getByRole("button", { name: "加载更多（剩余 5 条）" });
    fireEvent.click(more);
    expect(await screen.findByText(/分片正文 55/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /加载更多/ })).toBeNull();
  });

  it("点击查看上下文：抽屉打开并渲染 J4 时间线，关闭后移除", async () => {
    apiMock.getPublicCourses.mockResolvedValue([makeCourse()]);
    apiMock.getMaterials.mockResolvedValue([makeChunk(1), makeChunk(2)]);
    apiMock.getChunkContext.mockResolvedValue(CONTEXT);
    renderWorkbench();
    await screen.findByText(/分片正文 01/);
    // 打开第一个分片的上下文抽屉
    fireEvent.click(screen.getAllByRole("button", { name: "查看上下文" })[0]);
    expect(await screen.findByRole("dialog", { name: "分片上下文" })).toBeInTheDocument();
    // J4 时间线：父章节 + 下一分片（prev 恒 null 不渲染）
    expect(await screen.findByText("父章节")).toBeInTheDocument();
    expect(screen.getByText("父章节聚合内容")).toBeInTheDocument();
    expect(screen.getByText("下一分片")).toBeInTheDocument();
    expect(screen.getByText("下一分片内容")).toBeInTheDocument();
    expect(screen.queryByText("上一分片")).toBeNull();
    // 关闭：抽屉移除
    fireEvent.click(screen.getByRole("button", { name: "关闭" }));
    expect(screen.queryByRole("dialog")).toBeNull();
  });
});
