/**
 * 课程详情页测试（2026-08-31 改版重写）
 *
 * 改版后契约：
 * - 数据源为公开详情端点（getPublicCourseDetail，含排期列表），404 = 课程不存在/已下架
 * - Hero 展示公开信息（封面/标题/讲师/课时/评分/人数）+ 价格与购买状态机（契约 H.2.2）
 * - 主体两栏：左「课程介绍」（description 全文，空态占位）右「开课信息」（排期卡片/空态）
 * - 移除「问 AI 助教 / 进入学习 / 浏览资料」按钮与 J2 资料分片列表（改版拍板）
 * - 无自动登录弹窗：middleware 已门控游客，登录态由购买入口兜底
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import CourseDetailPage from "./page";
import { ApiError, NetworkError } from "@/lib/api";
import type { PublicCourseDetail } from "@/lib/types";

/** 数据层 mock：公开详情 / 我的课程 / 购买按用例注入 */
const apiMock = vi.hoisted(() => ({
  getPublicCourseDetail: vi.fn(),
  getMyCourses: vi.fn(),
  purchaseCourse: vi.fn(),
}));
/** 认证 mock：登录态可切换 + 弹窗操作记录 */
const authMock = vi.hoisted(() => ({ useAuth: vi.fn() }));
/** 路由 mock：/courses/[id] 动态段 */
const navMock = vi.hoisted(() => ({ params: { id: "c-1" } }));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    getPublicCourseDetail: apiMock.getPublicCourseDetail,
    getMyCourses: apiMock.getMyCourses,
    purchaseCourse: apiMock.purchaseCourse,
  };
});
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authMock.useAuth() }));
vi.mock("next/navigation", () => ({
  useParams: () => navMock.params,
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

/** 课程详情对象（公开详情 VO 形态，id 与路由段对齐；schedules 默认一期排期） */
function makeDetail(overrides: Partial<PublicCourseDetail> = {}): PublicCourseDetail {
  return {
    id: "c-1",
    title: "数据结构与算法",
    description: "第一段：课程核心内容概览。\n\n第二段：面向计算机专业学生的系统课程。",
    coverImage: "http://localhost:9000/b/c1.jpg",
    category: "计算机",
    instructorName: "王老师",
    duration: "32",
    rating: 4.5,
    learningCount: 256,
    price: 299,
    schedules: [
      {
        id: "s-1",
        startDate: "2026-09-01",
        endDate: "2026-12-20",
        scheduleType: "ONLINE",
        location: "线上直播",
        status: "UPCOMING",
        capacity: 200,
        enrolled: 35,
      },
    ],
    ...overrides,
  };
}

/** 渲染容器：独立 QueryClient（retry 关闭；可注入外部 client 供缓存失效断言） */
function renderDetail(client?: QueryClient) {
  const queryClient = client ?? new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <CourseDetailPage />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  apiMock.getPublicCourseDetail.mockReset();
  apiMock.getMyCourses.mockReset();
  apiMock.purchaseCourse.mockReset();
  authMock.useAuth.mockReset();
  authMock.useAuth.mockReturnValue(defaultAuth());
  // 默认未购（空我的课程），具体用例按需覆盖
  apiMock.getMyCourses.mockResolvedValue([]);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("课程详情页：加载与四态", () => {
  it("Loading：详情骨架（Hero 块 + 主体两栏灰条）", async () => {
    apiMock.getPublicCourseDetail.mockReturnValue(new Promise(() => {}));
    renderDetail();
    expect(screen.getByTestId("course-detail-skeleton")).toBeInTheDocument();
  });

  it("404：详情端点 404 → 空态 + 返回课程中心", async () => {
    apiMock.getPublicCourseDetail.mockRejectedValue(new ApiError(404, "课程不存在或已下架"));
    renderDetail();
    expect(await screen.findByText("课程不存在或已下架")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "返回课程中心" })).toHaveAttribute("href", "/courses");
  });

  it("非 404 错误：通用错误横幅 + 重试闭环恢复", async () => {
    apiMock.getPublicCourseDetail
      .mockRejectedValueOnce(new ApiError(503, "服务暂时不可用"))
      .mockResolvedValueOnce(makeDetail());
    renderDetail();
    expect(await screen.findByRole("alert")).toHaveTextContent("服务暂时不可用，请稍后重试");
    fireEvent.click(screen.getByRole("button", { name: "重试" }));
    expect(
      await screen.findByRole("heading", { level: 1, name: "数据结构与算法" }),
    ).toBeInTheDocument();
  });
});

describe("课程详情页：Hero 信息展示（改版 2026-08-31）", () => {
  it("Hero：封面 + 标题 + 讲师/课时/评分/人数 + 分类 + 面包屑返回", async () => {
    apiMock.getPublicCourseDetail.mockResolvedValue(makeDetail());
    renderDetail();
    expect(
      await screen.findByRole("heading", { level: 1, name: "数据结构与算法" }),
    ).toBeInTheDocument();
    expect(screen.getByAltText("数据结构与算法")).toBeInTheDocument();
    expect(screen.getByText("王老师")).toBeInTheDocument();
    expect(screen.getByText("32 课时")).toBeInTheDocument();
    expect(screen.getByText("4.5")).toBeInTheDocument();
    expect(screen.getByText("256 人学习")).toBeInTheDocument();
    // 分类：封面 overlay 徽章（aria-hidden）+ 信息区 chip 并存
    expect(screen.getAllByText("计算机").length).toBeGreaterThanOrEqual(1);
    // 面包屑返回课程中心（替代被移除按钮后的回退锚点）
    expect(screen.getByRole("link", { name: "课程中心" })).toHaveAttribute("href", "/courses");
    // 移除按钮回归：三按钮不再渲染
    expect(screen.queryByRole("button", { name: /问 AI 助教/ })).toBeNull();
    expect(screen.queryByRole("link", { name: /进入学习/ })).toBeNull();
    expect(screen.queryByRole("link", { name: /浏览资料/ })).toBeNull();
    // 资料分片区（数据库切片）不再渲染（改版拍板）
    expect(screen.queryByText("课程资料")).toBeNull();
  });

  it("无封面时 Hero 走学科渐变兜底", async () => {
    apiMock.getPublicCourseDetail.mockResolvedValue(makeDetail({ coverImage: null }));
    renderDetail();
    await screen.findByRole("heading", { level: 1, name: "数据结构与算法" });
    expect(screen.getByTestId("hero-cover-fallback")).toBeInTheDocument();
  });
});

describe("课程详情页：课程介绍与开课信息（改版 2026-08-31）", () => {
  it("课程介绍：描述按空行分段渲染全文", async () => {
    apiMock.getPublicCourseDetail.mockResolvedValue(makeDetail());
    renderDetail();
    await screen.findByRole("heading", { level: 1, name: "数据结构与算法" });
    expect(screen.getByRole("heading", { name: "课程介绍" })).toBeInTheDocument();
    expect(screen.getByText("第一段：课程核心内容概览。")).toBeInTheDocument();
    expect(screen.getByText("第二段：面向计算机专业学生的系统课程。")).toBeInTheDocument();
  });

  it("课程介绍为空：展示占位文案（不谎报有内容）", async () => {
    apiMock.getPublicCourseDetail.mockResolvedValue(makeDetail({ description: null }));
    renderDetail();
    await screen.findByRole("heading", { level: 1, name: "数据结构与算法" });
    expect(screen.getByText("讲师正在完善这门课程的详细介绍，敬请期待。")).toBeInTheDocument();
  });

  it("开课信息：排期卡片渲染开课/结课日期、类型、地点、状态与报名进度", async () => {
    apiMock.getPublicCourseDetail.mockResolvedValue(makeDetail());
    renderDetail();
    await screen.findByRole("heading", { level: 1, name: "数据结构与算法" });
    expect(screen.getByRole("heading", { name: "开课信息" })).toBeInTheDocument();
    expect(screen.getByText("2026 年 9 月 1 日")).toBeInTheDocument();
    expect(screen.getByText("至 2026 年 12 月 20 日")).toBeInTheDocument();
    expect(screen.getByText("线上开课")).toBeInTheDocument();
    expect(screen.getByText("线上直播")).toBeInTheDocument();
    expect(screen.getByText("未开课")).toBeInTheDocument();
    expect(screen.getByText("已报名 35 人 · 容量 200 人")).toBeInTheDocument();
  });

  it("开课信息为空：展示「暂无排期信息」空态（管理端未录入排期）", async () => {
    apiMock.getPublicCourseDetail.mockResolvedValue(makeDetail({ schedules: [] }));
    renderDetail();
    await screen.findByRole("heading", { level: 1, name: "数据结构与算法" });
    expect(screen.getByTestId("schedule-empty")).toBeInTheDocument();
    expect(screen.getByText("暂无排期信息，敬请期待")).toBeInTheDocument();
  });
});

describe("课程详情页：Hero 价格展示（契约 H.2.1 价格口径）", () => {
  it("未购课程：Hero 展示价格（单位元、去尾零）", async () => {
    apiMock.getPublicCourseDetail.mockResolvedValue(makeDetail({ price: 299.5 }));
    renderDetail();
    expect(await screen.findByTestId("course-price")).toHaveTextContent("¥299.5");
  });

  it("免费课程（price=0 / null）：价格位展示「免费」", async () => {
    apiMock.getPublicCourseDetail.mockResolvedValue(makeDetail({ price: 0 }));
    renderDetail();
    expect(await screen.findByTestId("course-price")).toHaveTextContent("免费");
  });
});

describe("课程详情页：购买状态机（契约 H.2.2）", () => {
  it("未登录：点「购买课程」弹登录窗登记 afterLogin，登录成功后自动继续购买", async () => {
    const openLoginDialog = vi.fn();
    authMock.useAuth.mockReturnValue(
      defaultAuth({ user: null, isAuthenticated: false, openLoginDialog }),
    );
    apiMock.getPublicCourseDetail.mockResolvedValue(makeDetail());
    renderDetail();
    fireEvent.click(await screen.findByRole("button", { name: /购买课程/ }));
    // 未登录不直接发购买请求，先弹登录窗
    expect(openLoginDialog).toHaveBeenCalled();
    expect(apiMock.purchaseCourse).not.toHaveBeenCalled();
    const options = openLoginDialog.mock.calls.at(-1)?.[0];
    expect(typeof options.afterLogin).toBe("function");
    // 登录成功回调触发自动续购
    options.afterLogin();
    await waitFor(() => {
      expect(apiMock.purchaseCourse).toHaveBeenCalledWith("c-1");
    });
  });

  it("未购·请求中：按钮转「购买中…」并禁用，重复点击不重复发请求", async () => {
    apiMock.getPublicCourseDetail.mockResolvedValue(makeDetail());
    // 悬挂的购买请求：停留 pending 态
    apiMock.purchaseCourse.mockReturnValue(new Promise(() => {}));
    renderDetail();
    const button = await screen.findByRole("button", { name: /购买课程/ });
    fireEvent.click(button);
    const pendingButton = await screen.findByRole("button", { name: /购买中/ });
    expect(pendingButton).toBeDisabled();
    fireEvent.click(pendingButton);
    expect(apiMock.purchaseCourse).toHaveBeenCalledTimes(1);
  });

  it("已购：展示「已购」徽章，不渲染购买按钮", async () => {
    apiMock.getPublicCourseDetail.mockResolvedValue(makeDetail());
    apiMock.getMyCourses.mockResolvedValue([makeDetail()]);
    renderDetail();
    expect(await screen.findByTestId("purchased-badge")).toHaveTextContent("已购");
    expect(screen.queryByRole("button", { name: /购买课程/ })).toBeNull();
  });

  it("成功：toast「购买成功」+ 失效 my-courses（写后读一致）+ 已购态即时刷新", async () => {
    apiMock.getPublicCourseDetail.mockResolvedValue(makeDetail());
    apiMock.getMyCourses.mockResolvedValueOnce([]).mockResolvedValueOnce([makeDetail()]);
    apiMock.purchaseCourse.mockResolvedValue({
      courseId: "c-1",
      status: "ACTIVE",
      purchased: true,
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");
    renderDetail(client);
    fireEvent.click(await screen.findByRole("button", { name: /购买课程/ }));
    expect(await screen.findByText("购买成功")).toBeInTheDocument();
    // 成功后已购徽章出现（my-courses 重取命中）、购买按钮消失
    expect(await screen.findByTestId("purchased-badge")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /购买课程/ })).toBeNull();
    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["my-courses"] });
    });
  });

  it("失败·404：错误横幅提示课程已下架，按钮恢复可点且可重试", async () => {
    apiMock.getPublicCourseDetail.mockResolvedValue(makeDetail());
    apiMock.purchaseCourse
      .mockRejectedValueOnce(new ApiError(404, "课程不存在或已下架"))
      .mockResolvedValueOnce({ courseId: "c-1", status: "ACTIVE", purchased: true });
    renderDetail();
    fireEvent.click(await screen.findByRole("button", { name: /购买课程/ }));
    const banner = await screen.findByRole("alert");
    expect(banner).toHaveTextContent("课程已下架或不存在，请刷新页面");
    const button = screen.getByRole("button", { name: /购买课程/ });
    expect(button).toBeEnabled();
    fireEvent.click(button);
    await screen.findByText("购买成功");
    expect(apiMock.purchaseCourse).toHaveBeenCalledTimes(2);
  });

  it("失败·网络错误：横幅提示检查网络，按钮恢复可点（可重试）", async () => {
    apiMock.getPublicCourseDetail.mockResolvedValue(makeDetail());
    apiMock.purchaseCourse.mockRejectedValue(new NetworkError());
    renderDetail();
    fireEvent.click(await screen.findByRole("button", { name: /购买课程/ }));
    const banner = await screen.findByRole("alert");
    expect(banner).toHaveTextContent("网络连接失败，请检查网络后重试");
    expect(screen.getByRole("button", { name: /购买课程/ })).toBeEnabled();
  });
});
