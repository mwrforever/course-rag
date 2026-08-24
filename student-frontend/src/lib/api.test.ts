/**
 * api client 核心测试（任务 7 TDD 先行用例）
 *
 * 覆盖 brief Step 1 五组核心场景：
 * 1. 成功响应 code===0 解包 data
 * 2. 401 单飞刷新：并发 3 个 401 请求 → refresh 仅 1 次 → 全部重放成功
 * 3. refresh 失败 → 调用登出回调且不重放
 * 4. 401 无 data 键容错（AuthInterceptor 401 响应体无 data 键）
 * 5. 网络错误抛出类型化错误（不清凭据）
 *
 * 另覆盖全部导出端点函数（URL/方法/请求体契约），保障 api.ts 行覆盖 100%。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { LoginResponse } from "./types";

/** 构造 fetch 假响应：不依赖 undici Response，仅实现本模块消费的形状 */
function res(status: number, body?: unknown): Response {
  const text = body === undefined ? "" : JSON.stringify(body);
  return {
    status,
    ok: status >= 200 && status < 300,
    text: () => Promise.resolve(text),
  } as Response;
}

/** 登录/刷新响应载荷工厂（userId 为 Long→string，R0 类型铁律） */
function loginData(at: string, rt: string): LoginResponse {
  return {
    accessToken: at,
    refreshToken: rt,
    userId: "1234567890",
    role: "STUDENT",
    displayName: "同学A",
  };
}

const fetchMock = vi.fn();

/** 每个用例动态 import 拿全新模块实例，隔离内存 AT / 单飞 promise / 登出回调状态 */
async function freshApi() {
  return await import("./api");
}

beforeEach(() => {
  vi.resetModules();
  vi.stubGlobal("fetch", fetchMock);
  localStorage.clear();
});

afterEach(() => {
  vi.unstubAllGlobals();
  fetchMock.mockReset();
});

describe("apiFetch 基础契约", () => {
  it("code===0 时解包 data 返回（数组载荷）", async () => {
    const api = await freshApi();
    fetchMock.mockResolvedValue(
      res(200, { code: 0, message: "ok", data: [{ id: "1", title: "课程" }] }),
    );
    await expect(api.getMyCourses()).resolves.toEqual([{ id: "1", title: "课程" }]);
    // 统一 baseURL /api/v1 相对路径（dev 经 Next rewrite 代理 8080）
    expect(String(fetchMock.mock.calls[0][0])).toBe("/api/v1/student/courses");
  });

  it("code!==0 时抛 ApiError（code 与业务码一致）", async () => {
    const api = await freshApi();
    fetchMock.mockResolvedValue(res(409, { code: 409, message: "会话已有活跃的 Run" }));
    const err = await api
      .apiFetch<unknown>("/student/chat/run-1/cancel", { method: "POST" })
      .catch((e: unknown) => e);
    expect(err).toBeInstanceOf(api.ApiError);
    expect((err as InstanceType<typeof api.ApiError>).code).toBe(409);
    expect((err as InstanceType<typeof api.ApiError>).message).toBe("会话已有活跃的 Run");
  });

  it("HTTP 错误且响应体非 JSON（如网关 HTML 错误页）抛 HTTP 状态 ApiError", async () => {
    const api = await freshApi();
    fetchMock.mockResolvedValue({
      status: 502,
      ok: false,
      text: () => Promise.resolve("<html>Bad Gateway</html>"),
    } as Response);
    const err = await api.getMyCourses().catch((e: unknown) => e);
    expect(err).toBeInstanceOf(api.ApiError);
    expect((err as InstanceType<typeof api.ApiError>).code).toBe(502);
  });

  it("空响应体（cancel 端点 ResponseEntity<Void>）容错返回 undefined", async () => {
    const api = await freshApi();
    fetchMock.mockResolvedValue(res(200));
    await expect(api.cancelRun("run-1")).resolves.toBeUndefined();
    expect(String(fetchMock.mock.calls[0][0])).toBe("/api/v1/student/chat/run-1/cancel");
    expect((fetchMock.mock.calls[0][1] as RequestInit).method).toBe("POST");
  });

  it("携带内存 Bearer 与 credentials include；FormData 不强设 JSON Content-Type", async () => {
    const api = await freshApi();
    api.setAccessToken("at-1");
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes("/chat/attachments")) {
        return res(200, {
          code: 0,
          message: "ok",
          data: [{ type: "image", url: "objkey", name: "a.png", size: "1024" }],
        });
      }
      if (url.includes("/student/sessions")) {
        return res(200, {
          code: 0,
          message: "ok",
          data: { records: [], total: "0", page: 1, size: 20 },
        });
      }
      throw new Error(`未预期的请求: ${url}`);
    });
    await api.getSessions(2, 20);
    const sessionCall = fetchMock.mock.calls.find((c) =>
      String(c[0]).includes("/student/sessions"),
    )!;
    expect((sessionCall[1] as RequestInit).credentials).toBe("include");
    expect(new Headers(sessionCall[1]?.headers).get("Authorization")).toBe("Bearer at-1");

    const file = new File(["x"], "a.png", { type: "image/png" });
    await api.uploadAttachments([file]);
    const uploadCall = fetchMock.mock.calls.find((c) =>
      String(c[0]).includes("/chat/attachments"),
    )!;
    expect(uploadCall[1]?.body).toBeInstanceOf(FormData);
    expect(new Headers(uploadCall[1]?.headers).get("Content-Type")).toBeNull();
  });
});

describe("401 单飞刷新（核心并发场景）", () => {
  it("并发 3 个 401 请求：refresh 仅调用 1 次，全部以新 AT 重放成功（401 响应无 data 键容错）", async () => {
    const api = await freshApi();
    api.setAccessToken("at-old");
    api.setRefreshToken("rt-old");
    const logoutSpy = vi.fn();
    api.setUnauthorizedHandler(logoutSpy);

    let refreshCalls = 0;
    const replayAuths: (string | null)[] = [];
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes("/auth/refresh")) {
        refreshCalls += 1;
        return res(200, { code: 0, message: "ok", data: loginData("at-new", "rt-new") });
      }
      if (url.includes("/student/sessions")) {
        if (refreshCalls === 0) {
          // 第一波（旧 AT）统一 401，且响应体无 data 键（AuthInterceptor 特例）
          return res(401, { code: 401, message: "令牌无效或已过期" });
        }
        replayAuths.push(new Headers(init?.headers).get("Authorization"));
        return res(200, {
          code: 0,
          message: "ok",
          data: { records: [], total: "0", page: 1, size: 20 },
        });
      }
      throw new Error(`未预期的请求: ${url}`);
    });

    const results = await Promise.all([
      api.getSessions(1, 20),
      api.getSessions(1, 20),
      api.getSessions(1, 20),
    ]);

    // 全部重放成功且解包正确
    expect(results).toHaveLength(3);
    for (const page of results) expect(page.total).toBe("0");
    // refresh 端点仅命中 1 次（单飞去重）
    expect(refreshCalls).toBe(1);
    // 原始 3 次 + refresh 1 次 + 重放 3 次
    expect(fetchMock).toHaveBeenCalledTimes(7);
    // 重放请求全部携带刷新后的新 AT
    expect(replayAuths).toEqual(["Bearer at-new", "Bearer at-new", "Bearer at-new"]);
    // RT 一次性旋转：新 RT 回写存储
    expect(localStorage.getItem("c_rt")).toBe("rt-new");
    expect(logoutSpy).not.toHaveBeenCalled();
  });

  it("refresh 失败：触发登出回调、清凭据且不重放原请求", async () => {
    const api = await freshApi();
    api.setAccessToken("at-old");
    api.setRefreshToken("rt-old");
    const logoutSpy = vi.fn();
    api.setUnauthorizedHandler(logoutSpy);

    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes("/auth/refresh")) {
        return res(401, { code: 401, message: "Refresh Token 无效或已过期" });
      }
      if (url.includes("/student/sessions")) {
        return res(401, { code: 401, message: "令牌无效或已过期" });
      }
      throw new Error(`未预期的请求: ${url}`);
    });

    const err = await api.getSessions(1, 20).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(api.ApiError);
    expect((err as InstanceType<typeof api.ApiError>).code).toBe(401);
    expect(logoutSpy).toHaveBeenCalledTimes(1);
    // 不重放：仅原请求 + refresh 各一次
    expect(fetchMock).toHaveBeenCalledTimes(2);
    // 凭据已清：AT 内存变量与 RT 存储均空
    expect(api.getAccessToken()).toBeNull();
    expect(localStorage.getItem("c_rt")).toBeNull();
  });

  it("无 RT 时 401 直接抛 401 并触发登出回调（不发 refresh 请求）", async () => {
    const api = await freshApi();
    const logoutSpy = vi.fn();
    api.setUnauthorizedHandler(logoutSpy);
    fetchMock.mockResolvedValue(res(401, { code: 401, message: "令牌无效或已过期" }));

    const err = await api.getSessions(1, 20).catch((e: unknown) => e);
    expect((err as InstanceType<typeof api.ApiError>).code).toBe(401);
    expect(logoutSpy).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

describe("网络错误类型化", () => {
  it("fetch 抛异常时抛 NetworkError 且不清凭据", async () => {
    const api = await freshApi();
    api.setAccessToken("at-1");
    api.setRefreshToken("rt-1");
    fetchMock.mockRejectedValue(new TypeError("Failed to fetch"));

    const err = await api.getMyCourses().catch((e: unknown) => e);
    expect(err).toBeInstanceOf(api.NetworkError);
    // 网络错误不清凭据不登出（设计 §3.2）
    expect(api.getAccessToken()).toBe("at-1");
    expect(localStorage.getItem("c_rt")).toBe("rt-1");
  });

  it("refresh 网络失败：抛 NetworkError、不清凭据不触发登出回调", async () => {
    const api = await freshApi();
    api.setRefreshToken("rt-1");
    const logoutSpy = vi.fn();
    api.setUnauthorizedHandler(logoutSpy);
    fetchMock.mockRejectedValue(new TypeError("Failed to fetch"));

    const err = await api.refresh().catch((e: unknown) => e);
    expect(err).toBeInstanceOf(api.NetworkError);
    expect(logoutSpy).not.toHaveBeenCalled();
    expect(localStorage.getItem("c_rt")).toBe("rt-1");
  });
});

describe("认证端点", () => {
  it("login 成功：body 携带 deviceType，AT 入内存 RT 入存储", async () => {
    const api = await freshApi();
    fetchMock.mockResolvedValue(
      res(200, { code: 0, message: "ok", data: loginData("at-1", "rt-1") }),
    );

    const data = await api.login("stu01", "pass123");
    expect(data.displayName).toBe("同学A");
    expect(api.getAccessToken()).toBe("at-1");
    expect(localStorage.getItem("c_rt")).toBe("rt-1");
    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toBe("/api/v1/auth/login");
    expect(JSON.parse(String(init?.body))).toEqual({
      username: "stu01",
      password: "pass123",
      deviceType: "WEB_DESKTOP",
    });
  });

  it("login 401（无 data 键）抛 ApiError 且不触发 refresh", async () => {
    const api = await freshApi();
    fetchMock.mockResolvedValue(res(401, { code: 401, message: "用户名或密码错误" }));

    const err = await api.login("stu01", "wrong-password").catch((e: unknown) => e);
    expect(err).toBeInstanceOf(api.ApiError);
    expect((err as InstanceType<typeof api.ApiError>).message).toBe("用户名或密码错误");
    // 登录端点自身不进入单飞刷新
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(api.getAccessToken()).toBeNull();
  });

  it("setRefreshToken(null) 移除 RT 存储（登出语义的存储层入口）", async () => {
    const api = await freshApi();
    api.setRefreshToken("rt-1");
    expect(localStorage.getItem("c_rt")).toBe("rt-1");
    api.setRefreshToken(null);
    expect(localStorage.getItem("c_rt")).toBeNull();
  });

  it("refresh(rt) 显式传参成功：新令牌落存储", async () => {
    const api = await freshApi();
    fetchMock.mockResolvedValue(
      res(200, { code: 0, message: "ok", data: loginData("at-2", "rt-2") }),
    );

    await api.refresh("rt-explicit");
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual({
      refreshToken: "rt-explicit",
    });
    expect(api.getAccessToken()).toBe("at-2");
    expect(localStorage.getItem("c_rt")).toBe("rt-2");
  });

  it("refresh 无可用 RT 抛 401（不发请求）", async () => {
    const api = await freshApi();
    const err = await api.refresh().catch((e: unknown) => e);
    expect((err as InstanceType<typeof api.ApiError>).code).toBe(401);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("refresh 响应缺 data 抛 401（容错分支）", async () => {
    const api = await freshApi();
    api.setRefreshToken("rt-1");
    fetchMock.mockResolvedValue(res(200, { code: 0, message: "ok" }));
    const err = await api.refresh().catch((e: unknown) => e);
    expect((err as InstanceType<typeof api.ApiError>).code).toBe(401);
  });

  it("logout 成功：调用后端并清本地凭据", async () => {
    const api = await freshApi();
    api.setAccessToken("at-1");
    api.setRefreshToken("rt-1");
    fetchMock.mockResolvedValue(res(200, { code: 0, message: "ok", data: null }));

    await api.logout();
    expect(String(fetchMock.mock.calls[0][0])).toBe("/api/v1/auth/logout");
    expect((fetchMock.mock.calls[0][1] as RequestInit).method).toBe("POST");
    expect(api.getAccessToken()).toBeNull();
    expect(localStorage.getItem("c_rt")).toBeNull();
  });

  it("logout 后端不可达（网络错误）仍清本地凭据", async () => {
    const api = await freshApi();
    api.setAccessToken("at-1");
    api.setRefreshToken("rt-1");
    fetchMock.mockRejectedValue(new TypeError("Failed to fetch"));

    await expect(api.logout()).resolves.toBeUndefined();
    expect(api.getAccessToken()).toBeNull();
    expect(localStorage.getItem("c_rt")).toBeNull();
  });
});

describe("SSE 重连端点", () => {
  it("reconnectChat：GET 携带 lastEventId（断流重连锚点），返回原始 Response", async () => {
    const api = await freshApi();
    fetchMock.mockResolvedValue(res(200));
    const response = await api.reconnectChat("run-1", 42);
    expect(response.status).toBe(200);
    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toBe("/api/v1/student/chat/run-1/reconnect?lastEventId=42");
    expect((init as RequestInit).method).toBe("GET");
  });

  it("reconnectChat：无 lastEventId（全量回放锚点）不带查询参数", async () => {
    const api = await freshApi();
    fetchMock.mockResolvedValue(res(200));
    await api.reconnectChat("run-2", null);
    expect(String(fetchMock.mock.calls[0][0])).toBe("/api/v1/student/chat/run-2/reconnect");
  });
});

describe("业务端点契约", () => {
  it("课程资料/资料库/分片上下文/会话系列端点 URL 与方法正确", async () => {
    const api = await freshApi();
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      const url = String(input);
      const ok = res(200, { code: 0, message: "ok", data: null });
      const okList = res(200, { code: 0, message: "ok", data: [] });
      const okPage = res(200, {
        code: 0,
        message: "ok",
        data: { records: [], total: "0", page: 1, size: 20 },
      });
      if (url.includes("/student/courses/9/materials")) return okList;
      if (url.includes("/student/knowledge-bases")) return okPage;
      if (url.includes("/student/chunks/7/context")) return ok;
      if (url.includes("/student/sessions/3/messages")) return okPage;
      if (url.includes("/student/sessions/3")) return ok;
      if (url.includes("/student/sessions")) return ok;
      if (url.includes("/student/feedbacks")) return ok;
      throw new Error(`未预期的请求: ${url}`);
    });

    await api.getMaterials("9");
    await api.getKbChunks(1, 20);
    await api.getChunkContext("7");
    await api.createSession("标题");
    await api.deleteSession("3");
    await api.getSessionMessages("3", 1, 200);
    await api.postFeedback({ sessionId: "1", messageId: "2", isLiked: true });

    const urls = fetchMock.mock.calls.map((c) => String(c[0]));
    expect(urls).toContain("/api/v1/student/courses/9/materials");
    expect(urls).toContain("/api/v1/student/knowledge-bases?page=1&size=20");
    expect(urls).toContain("/api/v1/student/chunks/7/context");
    expect(urls).toContain("/api/v1/student/sessions/3/messages?page=1&size=200");
    expect(urls).toContain("/api/v1/student/feedbacks");
    expect(urls).toContain("/api/v1/student/sessions/3");

    const createCall = fetchMock.mock.calls.find(
      (c) => String(c[0]).endsWith("/student/sessions") && (c[1] as RequestInit).method === "POST",
    )!;
    expect(JSON.parse(String(createCall[1]?.body))).toEqual({ title: "标题" });
    const deleteCall = fetchMock.mock.calls.find((c) =>
      String(c[0]).endsWith("/student/sessions/3"),
    )!;
    expect((deleteCall[1] as RequestInit).method).toBe("DELETE");
    const feedbackCall = fetchMock.mock.calls.find((c) =>
      String(c[0]).includes("/student/feedbacks"),
    )!;
    expect(JSON.parse(String(feedbackCall[1]?.body))).toEqual({
      sessionId: "1",
      messageId: "2",
      isLiked: true,
    });
  });

  it("postChat：返回原始 Response（SSE 流由上层解析），401 先刷新再重放", async () => {
    const api = await freshApi();
    api.setAccessToken("at-old");
    api.setRefreshToken("rt-1");
    let refreshCalls = 0;
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes("/auth/refresh")) {
        refreshCalls += 1;
        return res(200, { code: 0, message: "ok", data: loginData("at-new", "rt-2") });
      }
      if (url.endsWith("/student/chat")) {
        if (refreshCalls === 0) return res(401, { code: 401, message: "令牌无效或已过期" });
        return res(200);
      }
      throw new Error(`未预期的请求: ${url}`);
    });

    const response = await api.postChat({
      sessionId: null,
      query: "什么是哈希表？",
      attachments: null,
    });
    // 返回原始 Response 供 ReadableStream 手写 SSE 解析器消费
    expect(response.status).toBe(200);
    expect(refreshCalls).toBe(1);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(JSON.parse(String(fetchMock.mock.calls[2][1]?.body))).toEqual({
      sessionId: null,
      query: "什么是哈希表？",
      attachments: null,
    });
  });
});
