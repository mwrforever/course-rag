/**
 * 课程工作台测试（Task 9 TDD 先行用例）
 *
 * 设计 §1.5.3：课程 Hero（封面 4:3 + 信息 + 问 AI 助教 CTA + 浏览资料锚点）+
 * 资料分片列表（面包屑/3 行截断/页码 badge/查看上下文）+ 未选课 403 专属引导页
 * （「联系老师加入这门课程」+ 返回按钮）+ 分批渲染（首屏 50 + 加载更多，G10）。
 * 上下文抽屉（J4）在本页以集成方式验证开关与内容。
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import CourseWorkbenchPage from "./page";
import { ApiError } from "@/lib/api";
import type { ChunkContext, MaterialChunk, StudentCourse } from "@/lib/types";

/** 数据层 mock：J1 课程 / J2 资料 / J4 上下文按用例注入 */
const apiMock = vi.hoisted(() => ({
  getMyCourses: vi.fn(),
  getMaterials: vi.fn(),
  getChunkContext: vi.fn(),
}));
/** 路由 mock：/courses/[id] 动态段 */
const navMock = vi.hoisted(() => ({ params: { id: "c-1" } }));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    getMyCourses: apiMock.getMyCourses,
    getMaterials: apiMock.getMaterials,
    getChunkContext: apiMock.getChunkContext,
  };
});
vi.mock("next/navigation", () => ({
  useParams: () => navMock.params,
}));

/** 课程对象（J1 形态，id 与路由段对齐） */
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
  apiMock.getMyCourses.mockReset();
  apiMock.getMaterials.mockReset();
  apiMock.getChunkContext.mockReset();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("课程工作台：加载与四态", () => {
  it("Loading：Hero 骨架 + 资料列表骨架", async () => {
    apiMock.getMyCourses.mockReturnValue(new Promise(() => {}));
    apiMock.getMaterials.mockReturnValue(new Promise(() => {}));
    renderWorkbench();
    expect(screen.getByTestId("workbench-skeleton")).toBeInTheDocument();
    expect(screen.getByTestId("materials-skeleton")).toBeInTheDocument();
  });

  it("Error：资料加载失败 → 横幅 + 重试闭环恢复", async () => {
    apiMock.getMyCourses.mockResolvedValue([makeCourse()]);
    apiMock.getMaterials
      .mockRejectedValueOnce(new ApiError(503, "服务暂时不可用"))
      .mockResolvedValueOnce([makeChunk(1)]);
    renderWorkbench();
    expect(await screen.findByRole("alert")).toHaveTextContent("服务暂时不可用，请稍后重试");
    fireEvent.click(screen.getByRole("button", { name: "重试" }));
    expect(await screen.findByText(/分片正文 01/)).toBeInTheDocument();
  });

  it("课程不存在（J1 无此 id）：空态 + 返回我的课程", async () => {
    apiMock.getMyCourses.mockResolvedValue([makeCourse({ id: "c-other" })]);
    apiMock.getMaterials.mockResolvedValue([]);
    renderWorkbench();
    expect(await screen.findByText("课程不存在或已下架")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "返回我的课程" })).toHaveAttribute("href", "/courses");
  });
});

describe("课程工作台：未选课 403 专属引导页", () => {
  it("materials 403 → 渲染「联系老师加入这门课程」引导 + 返回按钮，不渲染课程内容", async () => {
    apiMock.getMyCourses.mockResolvedValue([makeCourse()]);
    apiMock.getMaterials.mockRejectedValue(new ApiError(403, "未选此课程，无权查看资料"));
    renderWorkbench();
    expect(await screen.findByText("还没有加入这门课程，请联系老师开通")).toBeInTheDocument();
    const back = screen.getByRole("link", { name: "返回我的课程" });
    expect(back).toHaveAttribute("href", "/courses");
    // 专属引导态：课程 Hero 与资料列表均不渲染
    expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
    expect(screen.queryByText("课程资料")).toBeNull();
  });

  it("非 403 错误不落入引导页（走通用 Error 横幅）", async () => {
    apiMock.getMyCourses.mockResolvedValue([makeCourse()]);
    apiMock.getMaterials.mockRejectedValue(new ApiError(500, "内部错误"));
    renderWorkbench();
    expect(await screen.findByRole("alert")).toHaveTextContent("服务暂时不可用，请稍后重试");
    expect(screen.queryByText("还没有加入这门课程，请联系老师开通")).toBeNull();
  });
});

describe("课程工作台：Hero 与资料列表", () => {
  it("Hero：封面 + 标题 + 讲师/课时/评分/人数 + category 徽章 + 双 CTA", async () => {
    apiMock.getMyCourses.mockResolvedValue([makeCourse()]);
    apiMock.getMaterials.mockResolvedValue([]);
    renderWorkbench();
    expect(
      await screen.findByRole("heading", { level: 1, name: "数据结构与算法" }),
    ).toBeInTheDocument();
    expect(screen.getByAltText("数据结构与算法")).toBeInTheDocument();
    expect(screen.getByText("王老师")).toBeInTheDocument();
    expect(screen.getByText("32 课时")).toBeInTheDocument();
    expect(screen.getByText("4.5")).toBeInTheDocument();
    expect(screen.getByText("256 人学习")).toBeInTheDocument();
    expect(screen.getByText("计算机")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /问 AI 助教/ })).toHaveAttribute("href", "/chat");
    expect(screen.getByRole("link", { name: /浏览资料/ })).toHaveAttribute("href", "#materials");
  });

  it("无封面时 Hero 走学科渐变兜底", async () => {
    apiMock.getMyCourses.mockResolvedValue([makeCourse({ coverImage: null })]);
    apiMock.getMaterials.mockResolvedValue([]);
    renderWorkbench();
    await screen.findByRole("heading", { level: 1, name: "数据结构与算法" });
    expect(screen.getByTestId("hero-cover-fallback")).toBeInTheDocument();
  });

  it("资料项渲染：面包屑、3 行截断内容、页码区间/单页 badge、null 页码省略", async () => {
    apiMock.getMyCourses.mockResolvedValue([makeCourse()]);
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
    apiMock.getMyCourses.mockResolvedValue([makeCourse()]);
    apiMock.getMaterials.mockResolvedValue([]);
    renderWorkbench();
    expect(await screen.findByText("这门课程还没有资料，稍后再来看看")).toBeInTheDocument();
  });
});

describe("课程工作台：分批渲染与上下文抽屉", () => {
  it("分批渲染：首屏 50 条 + 「加载更多（剩余 N 条）」逐批揭示", async () => {
    apiMock.getMyCourses.mockResolvedValue([makeCourse()]);
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
    apiMock.getMyCourses.mockResolvedValue([makeCourse()]);
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
