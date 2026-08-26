import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

/**
 * C 端路由门卫（设计 §3.1 + 审核补记 G6；登录弹窗化 2026-08-26 修订）
 *
 * 边界约束：middleware 运行于 edge runtime 无法验签 JWT，只做 httpOnly cookie 存在性检查
 * （不解析 token 内容）；真实鉴权一律以 API 401 全局拦截（api client 单飞刷新）为准。
 *
 * 规则（首页/课堂/详情页已公开化，登录弹窗承载认证入口）：
 * - 公开路由：/（首页）、/courses（课堂列表与课程详情）——未登录可直接浏览
 * - 受保护路由（/chat /profile）：无 AT cookie → 回首页 + ?login=1（首页自动弹登录窗）
 * - /login 已下线：老链接与书签兜底回首页
 */
const AUTH_COOKIE = "commerce_token";
/** 受保护路由前缀（课程对话与个人中心；课程浏览公开） */
const PROTECTED_PREFIXES = ["/chat", "/profile"];

/** 判定是否受保护路由（前缀匹配） */
function isProtected(pathname: string): boolean {
  return PROTECTED_PREFIXES.some((prefix) => pathname.startsWith(prefix));
}

/** 门卫逻辑：cookie 存在性检查 + 重定向 */
export function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const hasAuthCookie = request.cookies.has(AUTH_COOKIE);

  // 已下线页面兜底：/login 老链接与书签 → 回首页（登录经全局弹窗完成）
  if (pathname === "/login") {
    return NextResponse.redirect(new URL("/", request.url));
  }
  if (!hasAuthCookie && isProtected(pathname)) {
    // 未登录访问受保护路由：回首页并带上 login=1，首页据此自动打开登录弹窗
    const homeUrl = new URL("/", request.url);
    homeUrl.searchParams.set("login", "1");
    return NextResponse.redirect(homeUrl);
  }
  return NextResponse.next();
}

/** 仅对页面路由生效，排除 _next 静态资源与带扩展名的文件 */
export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico|.*\\..*).*)"],
};
