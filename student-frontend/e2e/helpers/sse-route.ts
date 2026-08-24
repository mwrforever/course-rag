import type { Page } from "@playwright/test";

/**
 * C 端 E2E route-mock 基建（设计 §3.2：全 route-mock，不依赖真实后端）
 *
 * - frame/heartbeat：按后端 MemoryStreamBridge 实际帧格式构造 SSE 文本
 *   （`id:<seq>\nevent:<name>\ndata:<原始JSON>\n\n`，data 不加引号）
 * - mockAuth：统一 mock 认证与公共接口（登录响应带 Set-Cookie，
 *   与后端 AuthController 真实行为一致，供 middleware 存在性检查）
 * - mockChatStream：拦截 POST /student/chat，按帧串 fulfill SSE 流；
 *   status=409 时按契约回 JSON 错误体
 */

/** 构造一条命名事件帧（id 可选——reconnect 补发路径无 id 行） */
export function frame(event: string, data: unknown, id?: number): string {
  return (id != null ? `id:${id}\n` : "") + `event:${event}\ndata:${JSON.stringify(data)}\n\n`;
}

/** 构造心跳注释行帧（后端 15s 一次，非命名事件） */
export function heartbeat(): string {
  return ":heartbeat\n\n";
}

/** 统一的登录用户（阿里：mock 数据，非真实账号） */
const E2E_USER = {
  accessToken: "at-e2e",
  refreshToken: "rt-e2e",
  userId: "1",
  role: "STUDENT",
  displayName: "林同学",
};

const JSON_OK = (data: unknown) => JSON.stringify({ code: 0, message: "success", data });

/** 公共 mock：认证三端点 + 首页/课程/会话/反馈相关 GET 接口 */
export async function mockApi(page: Page) {
  await page.route("**/api/v1/**", async (route) => {
    const req = route.request();
    const method = req.method();
    const path = new URL(req.url()).pathname;

    // 认证：登录/刷新成功回 Set-Cookie（与后端 AuthController 一致），登出幂等 200
    if (method === "POST" && (path.endsWith("/auth/login") || path.endsWith("/auth/refresh"))) {
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        headers: { "set-cookie": "commerce_token=at-e2e; Path=/; Max-Age=900; HttpOnly" },
        body: JSON_OK(E2E_USER),
      });
    }
    if (method === "POST" && path.endsWith("/auth/logout")) {
      return route.fulfill({ status: 200, contentType: "application/json", body: JSON_OK(null) });
    }

    // 我的课程（J1，首页/课程列表/工作台共用）
    if (method === "GET" && path.endsWith("/student/courses")) {
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON_OK([
          {
            id: "1",
            title: "数据结构与算法精讲",
            coverImage: null,
            category: "编程",
            instructorName: "张老师",
            duration: 12,
            rating: 4.8,
            learningCount: 236,
          },
          {
            id: "2",
            title: "Java 从入门到进阶",
            coverImage: null,
            category: "编程",
            instructorName: "李老师",
            duration: 20,
            rating: 4.5,
            learningCount: 89,
          },
        ]),
      });
    }

    // 会话列表（J6）：首页最近会话与 /sessions 共用
    if (method === "GET" && path.endsWith("/student/sessions")) {
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          code: 0,
          message: "success",
          data: {
            records: [
              {
                id: "10",
                title: "数据结构与算法咨询",
                status: "ACTIVE",
                lastMessageAt: "2026-08-24T09:30:00",
                createdAt: "2026-08-24T09:20:00",
              },
            ],
            total: "1",
            page: 1,
            size: 20,
          },
        }),
      });
    }

    // 其余请求：统一 200 空数据兜底（若用例需覆盖的接口缺失，会由具体断言暴露）
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON_OK(null) });
  });
}

/** 走一遍完整登录流程（mock 已就绪），跳转目标页后返回 */
export async function login(page: Page, redirectTo = "/") {
  await page.goto("/login");
  await page.fill("#username", "student");
  await page.fill("#password", "123456");
  await page.click('button[type="submit"]');
  await page.waitForURL(redirectTo);
}

/**
 * 拦截 POST /api/v1/student/chat：按帧串 fulfill SSE 流（单次响应，一次送达）。
 * status=409 时按契约回 JSON `{code:409,...}`（ConcurrentRunException 语义）。
 * delayMs：fulfill 前延迟（毫秒）——真实后端流持续数百 ms，前端 streaming 状态
 * 提交后才读到 EOF；mock 瞬时 fulfillment 会让 EOF 早于状态提交致断流路径不触发，
 * 故 cancel/reconnect/REPLAY_FAILED 等依赖断流语义的用例必须传 delayMs。
 * cancel/reconnect/attachments 端点由各用例按需自行 mock（避免悬挂 handler）。
 */
export async function mockChatStream(
  page: Page,
  sseBody: string,
  options: { status?: number; message?: string; delayMs?: number } = {},
) {
  const { status = 200, message = "会话已有活跃的 Run", delayMs = 0 } = options;
  await page.route("**/api/v1/student/chat", async (route) => {
    if (route.request().method() !== "POST") return route.fallback();
    if (delayMs > 0) {
      await new Promise((resolve) => setTimeout(resolve, delayMs));
    }
    if (status !== 200) {
      return route.fulfill({
        status,
        contentType: "application/json",
        body: JSON.stringify({ code: status, message }),
      });
    }
    await route.fulfill({
      status: 200,
      headers: { "Content-Type": "text/event-stream;charset=UTF-8" },
      body: sseBody,
    });
  });
}

/** 记录某一路径的请求次数（for 断言） */
export async function countRequests(page: Page, pathSuffix: string) {
  let count = 0;
  await page.route(`**${pathSuffix}`, async (route) => {
    count += 1;
    await route.fallback();
  });
  return { get: () => count };
}
