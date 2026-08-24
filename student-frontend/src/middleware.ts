import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

/**
 * C 端路由门卫（设计 §3.1 + 审核补记 G6）
 *
 * 边界约束：middleware 运行于 edge runtime 无法验签 JWT，只做 httpOnly cookie 存在性检查
 * （不解析 token 内容）；真实鉴权一律以 API 401 全局拦截（api client 单飞刷新）为准。
 *
 * 规则：
 * - 受保护路由（首页 / 与 /chat /sessions /profile /courses 前缀）：无 AT cookie → 跳 /login 并带 redirect 回跳参数
 * - 公开路由 /login：已持 AT cookie → 跳回首页
 */
const AUTH_COOKIE = "commerce_token";
/** 受保护路由前缀（/courses 前缀覆盖 /courses/[id]） */
const PROTECTED_PREFIXES = ["/chat", "/sessions", "/profile", "/courses"];

/** 判定是否受保护路由：首页精确匹配，其余按前缀 */
function isProtected(pathname: string): boolean {
  return pathname === "/" || PROTECTED_PREFIXES.some((prefix) => pathname.startsWith(prefix));
}

/** 门卫逻辑：cookie 存在性检查 + 重定向 */
export function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const hasAuthCookie = request.cookies.has(AUTH_COOKIE);

  if (!hasAuthCookie && isProtected(pathname)) {
    // 未登录访问受保护路由：跳登录页并携带回跳地址
    const loginUrl = new URL("/login", request.url);
    loginUrl.searchParams.set("redirect", pathname);
    return NextResponse.redirect(loginUrl);
  }
  if (hasAuthCookie && pathname === "/login") {
    // 已持凭据访问登录页：直接回首页
    return NextResponse.redirect(new URL("/", request.url));
  }
  return NextResponse.next();
}

/** 仅对页面路由生效，排除 _next 静态资源与带扩展名的文件 */
export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico|.*\\..*).*)"],
};
