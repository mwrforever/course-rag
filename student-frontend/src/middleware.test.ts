/**
 * middleware 路由门卫矩阵测试（认证刷新链路修复 2026-08-30 新增）
 *
 * 覆盖 cookie 存在性组合 × 路径的放行/重定向决策（纯存在性检查，不解析 JWT）：
 * 1. 有 AT cookie（commerce_token）→ 受保护路由放行（现状回归）
 * 2. 无 AT 但有 RT 提示 cookie（c_rt_live，AT 过期 + RT 有效的静默续期窗口）→ 放行，
 *    由客户端 AuthProvider 静默续期接管（核心新行为）
 * 3. 两者皆无访问受保护路由 → 307 重定向 /login?next=<原路径>（真匿名语义不变）
 * 4. 两者皆无访问公开首页 / → 放行（仅首页未登录可访问）
 * 5. 两者皆无访问公开登录页 /login → 放行（否则死循环）
 *
 * 环境说明：next/server 的 NextRequest/NextResponse 基于标准 Web Request/Response，
 * Node 18+ 与 jsdom 均可直接构造，无需 mock。
 */
import { NextRequest } from "next/server";
import { describe, expect, it } from "vitest";
import { middleware } from "./middleware";

/** 构造带指定 cookie 的 NextRequest（origin 固定本地 dev 端口，路径按用例传入） */
function makeRequest(pathname: string, cookies: Record<string, string>): NextRequest {
  const cookieHeader = Object.entries(cookies)
    .map(([name, value]) => `${name}=${value}`)
    .join("; ");
  return new NextRequest(new URL(`http://localhost:5000${pathname}`), {
    headers: cookieHeader ? { cookie: cookieHeader } : undefined,
  });
}

describe("middleware 路由门卫矩阵", () => {
  it("有 AT cookie（commerce_token）访问 /chat：放行（现状回归）", () => {
    const response = middleware(makeRequest("/chat", { commerce_token: "at-1" }));
    // 放行标记：NextResponse.next() 携带 x-middleware-next 头
    expect(response.headers.get("x-middleware-next")).toBe("1");
    expect(response.status).toBe(200);
  });

  it("无 AT 但有 c_rt_live（AT 过期 + RT 有效的续期窗口）访问 /chat：放行（核心新行为）", () => {
    const response = middleware(makeRequest("/chat", { c_rt_live: "1" }));
    expect(response.headers.get("x-middleware-next")).toBe("1");
    expect(response.status).toBe(200);
  });

  it("两者皆无访问 /chat：307 重定向 /login?next=/chat（真匿名语义不变）", () => {
    const response = middleware(makeRequest("/chat", {}));
    expect(response.status).toBe(307);
    expect(response.headers.get("location")).toBe("http://localhost:5000/login?next=%2Fchat");
  });

  it("两者皆无访问首页 /：放行（仅首页未登录可访问）", () => {
    const response = middleware(makeRequest("/", {}));
    expect(response.headers.get("x-middleware-next")).toBe("1");
  });

  it("两者皆无访问 /login：放行（登录页自身不受门控，否则重定向死循环）", () => {
    const response = middleware(makeRequest("/login", {}));
    expect(response.headers.get("x-middleware-next")).toBe("1");
  });

  it("两者皆无访问 /my-courses：307 重定向 /login?next=/my-courses（2026-08-31 新增受保护前缀）", () => {
    const response = middleware(makeRequest("/my-courses", {}));
    expect(response.status).toBe(307);
    expect(response.headers.get("location")).toBe("http://localhost:5000/login?next=%2Fmy-courses");
  });

  it("有 AT cookie 访问 /my-courses：放行（登录用户直达已购列表）", () => {
    const response = middleware(makeRequest("/my-courses", { commerce_token: "at-1" }));
    expect(response.headers.get("x-middleware-next")).toBe("1");
    expect(response.status).toBe(200);
  });
});
