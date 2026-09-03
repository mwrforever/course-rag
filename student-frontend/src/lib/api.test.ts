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

  it("携带内存 Bearer 与 credentials include（fetch 通道）", async () => {
    const api = await freshApi();
    api.setAccessToken("at-1");
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      const url = String(input);
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
  });
});

/** XHR 假实现（PERF-10a 上传通道测试驱动）：捕获请求形态，测试手工驱动进度/完成/失败 */
class FakeXhr {
  static instances: FakeXhr[] = [];
  method = "";
  url = "";
  withCredentials = false;
  headers: Record<string, string> = {};
  status = 0;
  responseText = "";
  sentBody: FormData | null = null;
  upload = {
    onprogress: null as null | ((event: { loaded: number; total?: number }) => void),
  };
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;

  open(method: string, url: string): void {
    this.method = method;
    this.url = url;
  }
  setRequestHeader(key: string, value: string): void {
    this.headers[key] = value;
  }
  send(body: FormData): void {
    this.sentBody = body;
    FakeXhr.instances.push(this);
  }
  /** 测试驱动：以指定状态与响应体完成（触发 onload） */
  complete(status: number, body?: unknown): void {
    this.status = status;
    this.responseText = body === undefined ? "" : JSON.stringify(body);
    this.onload?.();
  }
  /** 测试驱动：网络层失败（触发 onerror） */
  fail(): void {
    this.onerror?.();
  }
  /** 测试驱动：上报一段上传进度 */
  emitProgress(loaded: number, total?: number): void {
    this.upload.onprogress?.({ loaded, total });
  }
}

describe("uploadAttachments XHR 上传通道（PERF-10a）", () => {
  beforeEach(() => {
    FakeXhr.instances = [];
    vi.stubGlobal("XMLHttpRequest", FakeXhr);
  });

  it("multipart 经 XHR POST：携带 Bearer 与 withCredentials，进度回调触发且解包 data", async () => {
    const api = await freshApi();
    api.setAccessToken("at-1");
    const onProgress = vi.fn();
    const file = new File(["x"], "a.png", { type: "image/png" });
    const pending = api.uploadAttachments([file], onProgress);
    // XHR 已发出：形态断言（multipart 单请求、字段名 files、不强设 JSON Content-Type）
    const xhr = FakeXhr.instances[0];
    expect(xhr.method).toBe("POST");
    expect(xhr.url).toBe("/api/v1/student/chat/attachments");
    expect(xhr.withCredentials).toBe(true);
    expect(xhr.headers["Authorization"]).toBe("Bearer at-1");
    expect(xhr.headers["Content-Type"]).toBeUndefined();
    expect(xhr.sentBody).toBeInstanceOf(FormData);
    expect(xhr.sentBody?.get("files")).toBeInstanceOf(File);
    // 进度事件 → 0-100 整数百分比回调
    xhr.emitProgress(40, 100);
    xhr.emitProgress(100, 100);
    expect(onProgress).toHaveBeenNthCalledWith(1, 40);
    expect(onProgress).toHaveBeenNthCalledWith(2, 100);
    xhr.complete(200, {
      code: 0,
      message: "ok",
      data: [{ type: "image", url: "objkey", name: "a.png", size: "1" }],
    });
    await expect(pending).resolves.toEqual([
      { type: "image", url: "objkey", name: "a.png", size: "1" },
    ]);
  });

  it("401：单飞刷新成功后以新 AT 重放一次（refresh 走 fetch，仅一次）", async () => {
    const api = await freshApi();
    api.setAccessToken("at-old");
    api.setRefreshToken("rt-old");
    const logoutSpy = vi.fn();
    api.setUnauthorizedHandler(logoutSpy);
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      if (String(input).includes("/auth/refresh")) {
        return res(200, { code: 0, message: "ok", data: loginData("at-new", "rt-new") });
      }
      throw new Error(`未预期的请求: ${String(input)}`);
    });
    const file = new File(["x"], "a.pdf", { type: "application/pdf" });
    const pending = api.uploadAttachments([file]);
    FakeXhr.instances[0].complete(401, { code: 401, message: "令牌无效或已过期" });
    // 刷新完成后重放：等待第二个 XHR 发出，以新 Bearer 完成
    await vi.waitFor(() => expect(FakeXhr.instances).toHaveLength(2));
    expect(FakeXhr.instances[1].headers["Authorization"]).toBe("Bearer at-new");
    FakeXhr.instances[1].complete(200, {
      code: 0,
      message: "ok",
      data: [{ type: "document", url: "objkey", name: "a.pdf", size: "1" }],
    });
    await expect(pending).resolves.toEqual([
      { type: "document", url: "objkey", name: "a.pdf", size: "1" },
    ]);
    // 刷新仅一次且未触发登出回调（重放成功路径）
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(logoutSpy).not.toHaveBeenCalled();
  });

  it("业务码非 0：抛 ApiError（code 与业务码一致，错误语义与 fetch 通道对齐）", async () => {
    const api = await freshApi();
    const file = new File(["x"], "a.png", { type: "image/png" });
    const pending = api.uploadAttachments([file]);
    FakeXhr.instances[0].complete(413, { code: 413, message: "附件大小超限" });
    const err = await pending.catch((e: unknown) => e);
    expect(err).toBeInstanceOf(api.ApiError);
    expect((err as InstanceType<typeof api.ApiError>).code).toBe(413);
    expect((err as InstanceType<typeof api.ApiError>).message).toBe("附件大小超限");
  });

  it("HTTP 错误且响应体非 JSON：按状态码抛 ApiError；网络层失败抛 NetworkError", async () => {
    const api = await freshApi();
    const file = new File(["x"], "a.png", { type: "image/png" });
    const gateway = api.uploadAttachments([file]);
    FakeXhr.instances[0].status = 502;
    FakeXhr.instances[0].responseText = "<html>Bad Gateway</html>";
    FakeXhr.instances[0].onload?.();
    const err = await gateway.catch((e: unknown) => e);
    expect(err).toBeInstanceOf(api.ApiError);
    expect((err as InstanceType<typeof api.ApiError>).code).toBe(502);

    const offline = api.uploadAttachments([file]);
    FakeXhr.instances[1].fail();
    await expect(offline).rejects.toBeInstanceOf(api.NetworkError);
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

  it("setRefreshToken 写 RT 时同步写 c_rt_live 提示 cookie（有效期与 RT 对齐 7 天）；置 null 时清除", async () => {
    const api = await freshApi();
    // 监听 cookie 写入串以断言有效期属性（jsdom 的 document.cookie 不回读属性）
    const cookieSetter = vi.spyOn(document, "cookie", "set");
    api.setRefreshToken("rt-1");
    // middleware 存在性放行依据：AT cookie 过期但 RT 有效的窗口由该提示 cookie 放行
    expect(document.cookie).toContain("c_rt_live=1");
    expect(cookieSetter).toHaveBeenLastCalledWith(expect.stringContaining("Max-Age=604800"));
    // http（dev）环境不追加 Secure（回归：仅生产 https 追加，见 c_rt_live describe 的 https 用例）
    expect(cookieSetter).toHaveBeenLastCalledWith(expect.not.stringContaining("Secure"));
    api.setRefreshToken(null);
    expect(document.cookie).not.toContain("c_rt_live");
    cookieSetter.mockRestore();
  });

  it("clearCredentials 清 RT 时同步清 c_rt_live 提示 cookie（刷新失败/登出收口）", async () => {
    const api = await freshApi();
    api.setRefreshToken("rt-1");
    expect(document.cookie).toContain("c_rt_live=1");
    api.clearCredentials();
    expect(document.cookie).not.toContain("c_rt_live");
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

describe("c_rt_live 提示 cookie 加固（Secure 追加 + 残留清理收口，2026-08-31 N2 审核）", () => {
  it("https 环境 setRefreshToken 写入串追加 Secure（生产 https 防明文传输劫持）", async () => {
    const api = await freshApi();
    // 模拟生产 https 协议（dev http 不追加，见认证端点 describe 的 http 回归用例）
    const originalLocation = window.location;
    Object.defineProperty(window, "location", {
      value: { ...originalLocation, protocol: "https:" },
      configurable: true,
    });
    const cookieSetter = vi.spyOn(document, "cookie", "set");
    try {
      api.setRefreshToken("rt-1");
      expect(cookieSetter).toHaveBeenLastCalledWith(expect.stringContaining("c_rt_live=1"));
      expect(cookieSetter).toHaveBeenLastCalledWith(expect.stringContaining("Secure"));
    } finally {
      cookieSetter.mockRestore();
      Object.defineProperty(window, "location", { value: originalLocation, configurable: true });
    }
  });

  it("clearRtLiveCookie：写清除串（Max-Age=0），兜底清理残留提示 cookie", async () => {
    const api = await freshApi();
    // 残留场景预置：localStorage 无 RT 但 c_rt_live 残留（用户手清存储/ITP 分区，不触发常规清理路径）
    document.cookie = "c_rt_live=1; Path=/; Max-Age=604800; SameSite=Lax";
    const cookieSetter = vi.spyOn(document, "cookie", "set");
    api.clearRtLiveCookie();
    expect(cookieSetter).toHaveBeenCalledWith(expect.stringContaining("c_rt_live=;"));
    expect(cookieSetter).toHaveBeenCalledWith(expect.stringContaining("Max-Age=0"));
    cookieSetter.mockRestore();
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

describe("活跃 run 查询端点（M6.4 多会话并发续流）", () => {
  it("getActiveRun：GET active-run 并解包 runId（切回仍有 run 在生成的会话时续流入口）", async () => {
    const api = await freshApi();
    fetchMock.mockResolvedValue(res(200, { code: 0, message: "ok", data: { runId: "run-9" } }));
    await expect(api.getActiveRun("sess-9")).resolves.toBe("run-9");
    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toBe("/api/v1/student/chat/session/sess-9/active-run");
    // 认证通道与 fetch 通道同口径（cookie 凭证兜底）
    expect((init as RequestInit).credentials).toBe("include");
  });

  it("getActiveRun：无活跃 run 容错返回 null（runId=null 与 data 空体两形态）", async () => {
    const api = await freshApi();
    fetchMock.mockResolvedValueOnce(res(200, { code: 0, message: "ok", data: { runId: null } }));
    await expect(api.getActiveRun("sess-9")).resolves.toBeNull();
    fetchMock.mockResolvedValueOnce(res(200, { code: 0, message: "ok", data: null }));
    await expect(api.getActiveRun("sess-9")).resolves.toBeNull();
  });

  it("getActiveRun：会话不存在/非本人（404）与网络失败一律退化 null（不阻断历史回显）", async () => {
    const api = await freshApi();
    fetchMock.mockResolvedValueOnce(res(404, { code: 404, message: "会话不存在" }));
    await expect(api.getActiveRun("sess-x")).resolves.toBeNull();
    fetchMock.mockRejectedValueOnce(new TypeError("网络不可达"));
    await expect(api.getActiveRun("sess-9")).resolves.toBeNull();
  });
});

describe("业务端点契约", () => {
  it("公开课程详情/资料库/分片上下文/会话系列端点 URL 与方法正确", async () => {
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
      if (url.includes("/public/courses/c-9")) return okList;
      if (url.includes("/student/knowledge-bases")) return okPage;
      if (url.includes("/student/chunks/7/context")) return ok;
      if (url.includes("/student/sessions/3/messages")) return okPage;
      if (url.includes("/student/sessions/3")) return ok;
      if (url.includes("/student/sessions")) return ok;
      if (url.includes("/student/feedbacks")) return ok;
      throw new Error(`未预期的请求: ${url}`);
    });

    await api.getPublicCourseDetail("c-9");
    await api.getKbChunks(1, 20);
    await api.getChunkContext("7");
    await api.createSession("标题");
    await api.deleteSession("3");
    await api.getSessionMessages("3", 1, 200);
    await api.postFeedback({ sessionId: "1", messageId: "2", isLiked: true });

    const urls = fetchMock.mock.calls.map((c) => String(c[0]));
    expect(urls).toContain("/api/v1/public/courses/c-9");
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

describe("公开课程与会话管理端点（公开化 + 会话管理 2026-08-26）", () => {
  it("getPublicCourses → GET /public/courses 并解包列表", async () => {
    const api = await freshApi();
    fetchMock.mockResolvedValue(
      res(200, {
        code: 0,
        message: "ok",
        data: [{ id: "c1", title: "Java 入门", description: "简介", rating: 4.5 }],
      }),
    );

    const data = await api.getPublicCourses();

    expect(String(fetchMock.mock.calls[0][0])).toBe("/api/v1/public/courses");
    expect(data).toHaveLength(1);
  });

  it("getPublicCourseDetail → GET /public/courses/{id} 并解包详情（含排期列表）", async () => {
    const api = await freshApi();
    fetchMock.mockResolvedValue(
      res(200, {
        code: 0,
        message: "ok",
        data: {
          id: "c1",
          title: "Java 入门",
          description: "简介",
          schedules: [
            {
              id: "11",
              startDate: "2026-09-01",
              endDate: "2026-12-20",
              scheduleType: "ONLINE",
              location: "线上直播",
              status: "UPCOMING",
              capacity: 200,
              enrolled: 35,
            },
          ],
        },
      }),
    );

    const data = await api.getPublicCourseDetail("c1");

    // 详情端点 URL 契约（契约 C.2.2：GET，courseId 路径参数）
    expect(String(fetchMock.mock.calls[0][0])).toBe("/api/v1/public/courses/c1");
    expect(data.title).toBe("Java 入门");
    expect(data.schedules).toHaveLength(1);
    expect(data.schedules[0]).toEqual({
      id: "11",
      startDate: "2026-09-01",
      endDate: "2026-12-20",
      scheduleType: "ONLINE",
      location: "线上直播",
      status: "UPCOMING",
      capacity: 200,
      enrolled: 35,
    });
  });

  it("getPublicCourseDetail → 404 抛 ApiError(404)（课程不存在/已下架）", async () => {
    const api = await freshApi();
    fetchMock.mockResolvedValue(res(404, { code: 404, message: "课程不存在或已下架" }));

    await expect(api.getPublicCourseDetail("missing")).rejects.toMatchObject({ code: 404 });
  });

  it("purchaseCourse → POST /student/courses/{id}/purchase 并解包购买结果（幂等成功结构）", async () => {
    const api = await freshApi();
    fetchMock.mockResolvedValue(
      res(200, {
        code: 0,
        message: "success",
        data: { courseId: "1948633200000000001", status: "ACTIVE", purchased: true },
      }),
    );

    const data = await api.purchaseCourse("1948633200000000001");

    // 购买端点 URL 与方法契约（契约 B：POST，courseId 路径参数）
    expect(String(fetchMock.mock.calls[0][0])).toBe(
      "/api/v1/student/courses/1948633200000000001/purchase",
    );
    expect((fetchMock.mock.calls[0][1] as RequestInit).method).toBe("POST");
    expect(data).toEqual({ courseId: "1948633200000000001", status: "ACTIVE", purchased: true });
  });

  it("updateSessionTitle → PATCH /student/sessions/{id}，body 含 title", async () => {
    const api = await freshApi();
    fetchMock.mockResolvedValue(
      res(200, { code: 0, message: "ok", data: { id: "s1", title: "新标题" } }),
    );

    await api.updateSessionTitle("s1", "新标题");

    const call = fetchMock.mock.calls[0];
    expect(String(call[0])).toBe("/api/v1/student/sessions/s1");
    expect((call[1] as RequestInit).method).toBe("PATCH");
    expect(JSON.parse(String((call[1] as RequestInit).body))).toEqual({ title: "新标题" });
  });

  it("getSessions 带 keyword → URL 查询串携带 keyword", async () => {
    const api = await freshApi();
    fetchMock.mockResolvedValue(
      res(200, { code: 0, message: "ok", data: { records: [], total: "0", page: 1, size: 20 } }),
    );

    await api.getSessions(1, 20, "RAG");

    expect(String(fetchMock.mock.calls[0][0])).toBe(
      "/api/v1/student/sessions?page=1&size=20&keyword=RAG",
    );
  });
});

describe("注册端点契约（邮箱注册两段式 2026-08-27）", () => {
  it("sendRegisterCode：POST /auth/register/code 且请求体仅含邮箱；业务失败透传 ApiError", async () => {
    const api = await freshApi();
    fetchMock.mockResolvedValue(res(200, { code: 0, message: "success", data: null }));
    await expect(api.sendRegisterCode("B@Example.com")).resolves.toBeUndefined();
    expect(String(fetchMock.mock.calls[0][0])).toBe("/api/v1/auth/register/code");
    const init = fetchMock.mock.calls[0][1];
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body)).toEqual({ email: "B@Example.com" });

    fetchMock.mockResolvedValue(res(409, { code: 409, message: "该邮箱已注册，请直接登录" }));
    const err = await api.sendRegisterCode("b@example.com").catch((e: unknown) => e);
    expect(err).toBeInstanceOf(api.ApiError);
    expect((err as InstanceType<typeof api.ApiError>).message).toBe("该邮箱已注册，请直接登录");
  });

  it("registerAndLogin：POST /auth/register，成功即建立会话（AT 内存 + RT 入 localStorage）", async () => {
    const api = await freshApi();
    fetchMock.mockResolvedValue(
      res(200, { code: 0, message: "success", data: loginData("at-reg", "rt-reg") }),
    );
    const data = await api.registerAndLogin({
      email: "b@example.com",
      code: "654321",
      password: "Password-88",
    });
    expect(data.accessToken).toBe("at-reg");
    expect(localStorage.getItem("c_rt")).toBe("rt-reg");
    // 昵称缺省时请求体不携带 nickname 键（后端回退邮箱前缀语义）
    const init = fetchMock.mock.calls[0][1];
    expect(String(fetchMock.mock.calls[0][0])).toBe("/api/v1/auth/register");
    expect(JSON.parse(init.body)).toEqual({
      email: "b@example.com",
      code: "654321",
      password: "Password-88",
    });
  });

  it("registerAndLogin：携带昵称时透传；失败（400 验证码错误）不落任何凭据", async () => {
    const api = await freshApi();
    fetchMock.mockResolvedValue(res(400, { code: 400, message: "验证码错误", data: null }));
    await expect(
      api.registerAndLogin({
        email: "b@example.com",
        code: "000000",
        password: "Password-88",
        nickname: "同学B",
      }),
    ).rejects.toMatchObject({ code: 400, message: "验证码错误" });
    expect(localStorage.getItem("c_rt")).toBeNull();

    // 再验证带 nickname 的成功路径请求体结构（与上一用例的省略形态互补覆盖 100% 行）
    fetchMock.mockResolvedValue(
      res(200, { code: 0, message: "success", data: loginData("at2", "rt2") }),
    );
    await api.registerAndLogin({
      email: "b@example.com",
      code: "123456",
      password: "Password-88",
      nickname: "同学B",
    });
    expect(JSON.parse(fetchMock.mock.calls[fetchMock.mock.calls.length - 1][1].body)).toEqual({
      email: "b@example.com",
      code: "123456",
      password: "Password-88",
      nickname: "同学B",
    });
  });
});
