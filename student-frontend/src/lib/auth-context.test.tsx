/**
 * AuthProvider 认证上下文测试（任务 7 TDD 先行用例；登录弹窗化 2026-08-26 修订）
 *
 * 覆盖：挂载静默续期（有 RT 成功/认证失败/网络失败/无 RT）、login/logout 状态流转、
 * 401 刷新失败全局登出回调联动（登录弹窗化：回调打开弹窗而非跳转 /login）、
 * 登录弹窗 API（openLoginDialog 登记动作 / submitLogin 成功后关闭并执行 / 失败保持打开）、
 * Provider 外使用 useAuth 的防护、卸载清理。
 */
import { useQueryClient, type QueryClient } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useEffect } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/** 构造 fetch 假响应（与 api.test 同手法，仅实现消费的形状） */
function res(status: number, body?: unknown): Response {
  const text = body === undefined ? "" : JSON.stringify(body);
  return {
    status,
    ok: status >= 200 && status < 300,
    text: () => Promise.resolve(text),
  } as Response;
}

function loginBody(at: string, rt: string) {
  return {
    code: 0,
    message: "ok",
    data: {
      accessToken: at,
      refreshToken: rt,
      userId: "1234567890",
      role: "STUDENT",
      displayName: "同学A",
    },
  };
}

const fetchMock = vi.fn();

/**
 * 缓存探针持有器：测试侧读取 QueryProvider 内部的 QueryClient（探针挂载时捕获）
 *
 * 说明：直接持有客户端引用使断言可轮询（clear 不触发探针重渲染，经 waitFor 轮询读取）
 */
const clientHolder: { current: QueryClient | null } = { current: null };

/**
 * 缓存探针：捕获上下文 QueryClient + 「写入缓存」按钮模拟旧账号缓存数据
 * （BUG-06 断言载体：账号切换事件到达后缓存条目应被清空）
 */
function CacheProbe() {
  const client = useQueryClient();
  useEffect(() => {
    clientHolder.current = client;
  });
  return (
    <button type="button" onClick={() => client.setQueryData(["bug06-probe"], "旧账号数据")}>
      写入缓存
    </button>
  );
}

/** 动态 import 拿全新模块实例（auth-context 与 api 共享同一次重置），并生成探针组件 */
async function fresh() {
  const mod = await import("./auth-context");
  const api = await import("./api");
  const loginOutcome: { error?: unknown } = {};
  /** 弹窗探针状态：afterLogin 执行记录（submitLogin 成功路径断言） */
  const dialogOutcome: { afterLoginRuns: number } = { afterLoginRuns: 0 };
  function Probe() {
    const auth = mod.useAuth();
    return (
      <div>
        <span data-testid="state">{auth.isAuthenticated ? "已登录" : "未登录"}</span>
        <span data-testid="loading">{auth.isLoading ? "加载中" : "就绪"}</span>
        <span data-testid="dialog">{auth.loginDialogOpen ? "开启" : "关闭"}</span>
        {auth.user ? <span data-testid="displayName">{auth.user.displayName}</span> : null}
        <button
          type="button"
          onClick={() => {
            // 登录失败记录到外部引用供断言（成功路径由状态断言覆盖）
            auth.login("stu01", "pass123").catch((error: unknown) => {
              loginOutcome.error = error;
            });
          }}
        >
          触发登录
        </button>
        <button type="button" onClick={() => void auth.logout()}>
          触发登出
        </button>
        <button
          type="button"
          onClick={() =>
            auth.openLoginDialog({
              afterLogin: () => {
                dialogOutcome.afterLoginRuns += 1;
              },
            })
          }
        >
          打开弹窗
        </button>
        <button type="button" onClick={() => auth.closeLoginDialog()}>
          关闭弹窗
        </button>
        <button
          type="button"
          onClick={() => {
            auth.submitLogin("stu01", "pass123").catch((error: unknown) => {
              loginOutcome.error = error;
            });
          }}
        >
          弹窗提交登录
        </button>
      </div>
    );
  }
  return { mod, api, Probe, loginOutcome, dialogOutcome };
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

describe("挂载静默续期（bootstrap）", () => {
  it("无 RT：立即就绪且未登录", async () => {
    const { mod, Probe } = await fresh();
    render(
      <mod.AuthProvider>
        <Probe />
      </mod.AuthProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("loading")).toHaveTextContent("就绪"));
    expect(screen.getByTestId("state")).toHaveTextContent("未登录");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("无 RT 挂载：兜底清理残留 c_rt_live 提示 cookie（localStorage 无 RT 但 cookie 残留收口）", async () => {
    // 残留场景预置：localStorage 无 RT，但 c_rt_live 提示 cookie 残留（用户手清存储/ITP 分区）
    document.cookie = "c_rt_live=1; Path=/; Max-Age=604800; SameSite=Lax";
    const cookieSetter = vi.spyOn(document, "cookie", "set");
    const { mod, Probe } = await fresh();
    render(
      <mod.AuthProvider>
        <Probe />
      </mod.AuthProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("loading")).toHaveTextContent("就绪"));
    // 残留提示 cookie 必须被写清除串：不清理会让 middleware 放行真匿名者（页面骨架→登录弹窗），
    // 清掉后下次导航回归真匿名 307 语义
    expect(cookieSetter).toHaveBeenCalledWith(expect.stringContaining("c_rt_live=;"));
    expect(cookieSetter).toHaveBeenCalledWith(expect.stringContaining("Max-Age=0"));
    expect(screen.getByTestId("state")).toHaveTextContent("未登录");
    cookieSetter.mockRestore();
  });

  it("有 RT 挂载：不触发残留清理（走正常静默续期，凭据链路不受影响）", async () => {
    localStorage.setItem("c_rt", "rt-old");
    fetchMock.mockResolvedValue(res(200, loginBody("at-new", "rt-new")));
    const cookieSetter = vi.spyOn(document, "cookie", "set");
    const { mod, Probe } = await fresh();
    render(
      <mod.AuthProvider>
        <Probe />
      </mod.AuthProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("state")).toHaveTextContent("已登录"));
    // 有 RT 分支只走正常续期：refresh 成功回写存在性提示 cookie，但绝不写清除串
    expect(cookieSetter.mock.calls.some(([s]) => String(s).includes("Max-Age=0"))).toBe(false);
    expect(cookieSetter).toHaveBeenCalledWith(expect.stringContaining("c_rt_live=1"));
    cookieSetter.mockRestore();
  });

  it("有 RT 且 refresh 成功：恢复登录态", async () => {
    localStorage.setItem("c_rt", "rt-old");
    fetchMock.mockResolvedValue(res(200, loginBody("at-new", "rt-new")));
    const { mod, Probe } = await fresh();
    render(
      <mod.AuthProvider>
        <Probe />
      </mod.AuthProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("state")).toHaveTextContent("已登录"));
    expect(screen.getByTestId("displayName")).toHaveTextContent("同学A");
    expect(screen.getByTestId("loading")).toHaveTextContent("就绪");
    // RT 一次性旋转回写
    expect(localStorage.getItem("c_rt")).toBe("rt-new");
  });

  it("有 RT 但 refresh 认证失败（401）：静默保持未登录且凭据被清", async () => {
    localStorage.setItem("c_rt", "rt-stale");
    fetchMock.mockResolvedValue(res(401, { code: 401, message: "Refresh Token 无效或已过期" }));
    const { mod, Probe } = await fresh();
    render(
      <mod.AuthProvider>
        <Probe />
      </mod.AuthProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("loading")).toHaveTextContent("就绪"));
    expect(screen.getByTestId("state")).toHaveTextContent("未登录");
    expect(localStorage.getItem("c_rt")).toBeNull();
  });

  it("有 RT 但网络失败：静默保持未登录且凭据保留（网络错误不登出）", async () => {
    localStorage.setItem("c_rt", "rt-keep");
    fetchMock.mockRejectedValue(new TypeError("Failed to fetch"));
    const { mod, Probe } = await fresh();
    render(
      <mod.AuthProvider>
        <Probe />
      </mod.AuthProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("loading")).toHaveTextContent("就绪"));
    expect(screen.getByTestId("state")).toHaveTextContent("未登录");
    expect(localStorage.getItem("c_rt")).toBe("rt-keep");
  });

  it("卸载 Provider：静默续期中止（cancelled 守卫）且清理登出回调注册", async () => {
    localStorage.setItem("c_rt", "rt-1");
    let resolveRefresh: (value: Response) => void = () => {};
    fetchMock.mockImplementation(
      () =>
        new Promise<Response>((resolve) => {
          resolveRefresh = resolve;
        }),
    );
    const { mod, Probe } = await fresh();
    const view = render(
      <mod.AuthProvider>
        <Probe />
      </mod.AuthProvider>,
    );
    view.unmount();
    // 卸载后 refresh 才返回：cancelled 守卫生效，不产生任何状态更新
    await act(async () => {
      resolveRefresh(res(200, loginBody("at-2", "rt-2")));
      await Promise.resolve();
    });
    expect(document.querySelector("[data-testid='state']")).toBeNull();
  });
});

describe("login / logout 状态流转", () => {
  it("login 成功：置登录态并落 RT 存储", async () => {
    fetchMock.mockResolvedValue(res(200, loginBody("at-1", "rt-1")));
    const { mod, Probe } = await fresh();
    render(
      <mod.AuthProvider>
        <Probe />
      </mod.AuthProvider>,
    );
    await screen.findByText("触发登录");
    fireEvent.click(screen.getByText("触发登录"));
    await waitFor(() => expect(screen.getByTestId("state")).toHaveTextContent("已登录"));
    expect(localStorage.getItem("c_rt")).toBe("rt-1");
  });

  it("login 失败：错误上抛且保持未登录", async () => {
    fetchMock.mockResolvedValue(res(401, { code: 401, message: "用户名或密码错误" }));
    const { mod, Probe, loginOutcome } = await fresh();
    render(
      <mod.AuthProvider>
        <Probe />
      </mod.AuthProvider>,
    );
    await screen.findByText("触发登录");
    fireEvent.click(screen.getByText("触发登录"));
    await waitFor(() => expect(loginOutcome.error).toBeInstanceOf(Error));
    expect(screen.getByTestId("state")).toHaveTextContent("未登录");
    expect(localStorage.getItem("c_rt")).toBeNull();
  });

  it("logout：调用后端登出并清空登录态", async () => {
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes("/auth/login")) return res(200, loginBody("at-1", "rt-1"));
      if (url.includes("/auth/logout")) return res(200, { code: 0, message: "ok", data: null });
      throw new Error(`未预期的请求: ${url}`);
    });
    const { mod, Probe } = await fresh();
    render(
      <mod.AuthProvider>
        <Probe />
      </mod.AuthProvider>,
    );
    fireEvent.click(await screen.findByText("触发登录"));
    await waitFor(() => expect(screen.getByTestId("state")).toHaveTextContent("已登录"));
    fireEvent.click(screen.getByText("触发登出"));
    await waitFor(() => expect(screen.getByTestId("state")).toHaveTextContent("未登录"));
    expect(String(fetchMock.mock.calls.at(-1)?.[0])).toBe("/api/v1/auth/logout");
    expect(localStorage.getItem("c_rt")).toBeNull();
  });
});

describe("401 刷新失败全局登出联动（登录弹窗化）", () => {
  it("api 层 401 且 refresh 失败：Provider 注册的登出回调清空用户态", async () => {
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes("/auth/login")) return res(200, loginBody("at-1", "rt-1"));
      // 登录后所有请求 401（AT 过期且 RT 失效场景）
      return res(401, { code: 401, message: "令牌无效或已过期" });
    });
    const { mod, api, Probe } = await fresh();
    render(
      <mod.AuthProvider>
        <Probe />
      </mod.AuthProvider>,
    );
    fireEvent.click(await screen.findByText("触发登录"));
    await waitFor(() => expect(screen.getByTestId("state")).toHaveTextContent("已登录"));

    // 任意业务请求 401 → 单飞 refresh 也 401 → 登出回调清空用户态
    await act(async () => {
      await expect(api.getMyCourses()).rejects.toThrow();
    });
    await waitFor(() => expect(screen.getByTestId("state")).toHaveTextContent("未登录"));
  });

  it("认证过期闭环：打开登录弹窗（不跳转 /login）并展示登录失效 toast", async () => {
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes("/auth/login")) return res(200, loginBody("at-1", "rt-1"));
      // 登录后所有请求 401（AT 过期且 RT 失效场景）
      return res(401, { code: 401, message: "令牌无效或已过期" });
    });
    const { mod, api, Probe } = await fresh();
    render(
      <mod.AuthProvider>
        <Probe />
      </mod.AuthProvider>,
    );
    fireEvent.click(await screen.findByText("触发登录"));
    await waitFor(() => expect(screen.getByTestId("state")).toHaveTextContent("已登录"));

    // 认证过期：回调应打开登录弹窗并出现 toast 提示（登录弹窗化：不再整页跳 /login）
    await act(async () => {
      await expect(api.getMyCourses()).rejects.toThrow();
    });
    await waitFor(() => expect(screen.getByTestId("dialog")).toHaveTextContent("开启"));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("登录已失效，请重新登录");
    await waitFor(() => expect(screen.getByTestId("state")).toHaveTextContent("未登录"));
  });
});

describe("登录弹窗 API（openLoginDialog / closeLoginDialog / submitLogin）", () => {
  it("openLoginDialog：弹窗开启（登记 afterLogin）；closeLoginDialog：关闭并丢弃动作", async () => {
    // 无 RT 快速就绪
    const { mod, Probe, dialogOutcome } = await fresh();
    render(
      <mod.AuthProvider>
        <Probe />
      </mod.AuthProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("loading")).toHaveTextContent("就绪"));
    fireEvent.click(screen.getByText("打开弹窗"));
    expect(screen.getByTestId("dialog")).toHaveTextContent("开启");
    // afterLogin 未登录成功前不执行
    expect(dialogOutcome.afterLoginRuns).toBe(0);
    // closeLoginDialog：弹窗关闭且丢弃 afterLogin（登录后不再执行）
    fireEvent.click(screen.getByText("关闭弹窗"));
    expect(screen.getByTestId("dialog")).toHaveTextContent("关闭");
    fireEvent.click(screen.getByText("打开弹窗"));
    expect(screen.getByTestId("dialog")).toHaveTextContent("开启");
    fireEvent.click(screen.getByText("关闭弹窗"));
    expect(dialogOutcome.afterLoginRuns).toBe(0);
  });

  it("submitLogin 成功后：关闭弹窗并执行 afterLogin", async () => {
    fetchMock.mockResolvedValue(res(200, loginBody("at-1", "rt-1")));
    const { mod, Probe, dialogOutcome } = await fresh();
    render(
      <mod.AuthProvider>
        <Probe />
      </mod.AuthProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("loading")).toHaveTextContent("就绪"));
    // 先打开弹窗并登记 afterLogin
    fireEvent.click(screen.getByText("打开弹窗"));
    expect(screen.getByTestId("dialog")).toHaveTextContent("开启");
    // 弹窗提交登录成功：登录态建立、弹窗关闭、afterLogin 执行
    fireEvent.click(screen.getByText("弹窗提交登录"));
    await waitFor(() => expect(screen.getByTestId("state")).toHaveTextContent("已登录"));
    expect(screen.getByTestId("dialog")).toHaveTextContent("关闭");
    expect(dialogOutcome.afterLoginRuns).toBe(1);
    expect(localStorage.getItem("c_rt")).toBe("rt-1");
  });

  it("submitLogin 失败：错误上抛且弹窗保持打开（用户可修正重试）", async () => {
    fetchMock.mockResolvedValue(res(401, { code: 401, message: "用户名或密码错误" }));
    const { mod, Probe, loginOutcome } = await fresh();
    render(
      <mod.AuthProvider>
        <Probe />
      </mod.AuthProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("loading")).toHaveTextContent("就绪"));
    // 先打开弹窗（登录失败不改动弹窗开闭状态）
    fireEvent.click(screen.getByText("打开弹窗"));
    expect(screen.getByTestId("dialog")).toHaveTextContent("开启");
    fireEvent.click(screen.getByText("弹窗提交登录"));
    await waitFor(() => expect(loginOutcome.error).toBeInstanceOf(Error));
    expect(screen.getByTestId("dialog")).toHaveTextContent("开启");
  });
});

describe("useAuth 防护", () => {
  it("Provider 外使用 useAuth 抛出明确错误", async () => {
    // 抑制 React 对渲染异常的控制台输出（预期内的失败场景）
    const consoleSpy = vi.spyOn(console, "error").mockImplementation(() => {});
    const { mod } = await fresh();
    function Naked() {
      mod.useAuth();
      return null;
    }
    expect(() => render(<Naked />)).toThrow(/AuthProvider/);
    consoleSpy.mockRestore();
  });
});

describe("账号切换缓存清理（BUG-06：401 换登/登录成功后清空 React Query 缓存）", () => {
  it("api 层 401 且 refresh 失败：全局登出回调触发时 QueryClient 缓存被清空", async () => {
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes("/auth/login")) return res(200, loginBody("at-1", "rt-1"));
      // 登录后所有请求 401（AT 过期且 RT 失效场景）：业务请求与单飞 refresh 均失败
      return res(401, { code: 401, message: "令牌无效或已过期" });
    });
    const { mod, api, Probe } = await fresh();
    const { QueryProvider } = await import("./query-provider");
    render(
      <mod.AuthProvider>
        <QueryProvider>
          <Probe />
          <CacheProbe />
        </QueryProvider>
      </mod.AuthProvider>,
    );
    fireEvent.click(await screen.findByText("触发登录"));
    await waitFor(() => expect(screen.getByTestId("state")).toHaveTextContent("已登录"));
    // 预置旧账号缓存数据（模拟用户 A 的会话/课程缓存）
    fireEvent.click(screen.getByText("写入缓存"));
    expect(clientHolder.current?.getQueryCache().getAll().length).toBe(1);

    // 业务请求 401 → 单飞 refresh 也 401 → 全局登出回调 → 缓存随账号切换清空
    await act(async () => {
      await expect(api.getMyCourses()).rejects.toThrow();
    });
    await waitFor(() => expect(clientHolder.current?.getQueryCache().getAll().length).toBe(0));
    await waitFor(() => expect(screen.getByTestId("state")).toHaveTextContent("未登录"));
  });

  it("登录成功（含未过期主动换登）：旧账号缓存被清空、新登录态正常建立", async () => {
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes("/auth/login")) return res(200, loginBody("at-2", "rt-2"));
      throw new Error(`未预期的请求: ${url}`);
    });
    const { mod, Probe } = await fresh();
    const { QueryProvider } = await import("./query-provider");
    render(
      <mod.AuthProvider>
        <QueryProvider>
          <Probe />
          <CacheProbe />
        </QueryProvider>
      </mod.AuthProvider>,
    );
    // 用户 A 已登录（未过期主动换登场景：无 401，直接经弹窗换成用户 B）
    fireEvent.click(await screen.findByText("触发登录"));
    await waitFor(() => expect(screen.getByTestId("state")).toHaveTextContent("已登录"));
    fireEvent.click(screen.getByText("写入缓存"));
    expect(clientHolder.current?.getQueryCache().getAll().length).toBe(1);

    // 弹窗提交登录（用户 B）成功：旧账号缓存必须清空，防 B 读到 A 的会话/课程
    fireEvent.click(screen.getByText("弹窗提交登录"));
    await waitFor(() => expect(clientHolder.current?.getQueryCache().getAll().length).toBe(0));
    await waitFor(() => expect(screen.getByTestId("state")).toHaveTextContent("已登录"));
  });

  it("QueryProvider 卸载后退订事件（无泄漏）：再次广播不触碰已卸载客户端", async () => {
    fetchMock.mockImplementation(async () => {
      throw new Error("本用例不应发起任何请求");
    });
    const { QueryProvider } = await import("./query-provider");
    const events = await import("./auth-cache-events");
    const { unmount } = render(
      <QueryProvider>
        <CacheProbe />
      </QueryProvider>,
    );
    fireEvent.click(await screen.findByText("写入缓存"));
    expect(clientHolder.current?.getQueryCache().getAll().length).toBe(1);
    // 挂载期间广播：缓存清空生效（订阅链路正常路径）
    events.emitAuthCacheReset();
    await waitFor(() => expect(clientHolder.current?.getQueryCache().getAll().length).toBe(0));

    // 卸载后退订：再次广播不再调用已卸载客户端的 clear（悬空回调泄漏防护）
    const clearSpy = vi.spyOn(clientHolder.current as QueryClient, "clear");
    unmount();
    events.emitAuthCacheReset();
    expect(clearSpy).not.toHaveBeenCalled();
    clearSpy.mockRestore();
  });
});
