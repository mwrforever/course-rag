import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

/**
 * C 端路由门卫（设计 §3.1 + 审核补记 G6；登录弹窗化 2026-08-26 修订）
 *
 * 边界约束：middleware 运行于 edge runtime 无法验签 JWT，只做 httpOnly cookie 存在性检查
 * （不解析 token 内容）；真实鉴权一律以 API 401 全局拦截（api client 单飞刷新）为准。
 *
 * 规则（2026-08-27 审查 m4 修订：兜底直引独立登录页）：
 * - 公开路由：/（首页）、/courses、/login（独立登录页）——未登录可直接访问
 * - 受保护路由（/chat /profile）：无 AT cookie → /login?next=<原路径>
 *   （登录成功后按 next 白名单站内回跳；不再借道首页 ?login=1 弹窗，
 *   深度链路与独立登录页目标一致）
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

  if (!hasAuthCookie && isProtected(pathname)) {
    // 未登录访问受保护路由：直引独立登录页并携带原路径（next 由登录侧做站内白名单校验后回跳）
    const loginUrl = new URL("/login", request.url);
    loginUrl.searchParams.set("next", pathname);
    return NextResponse.redirect(loginUrl);
  }
  return NextResponse.next();
}

/** 仅对页面路由生效，排除 _next 静态资源与带扩展名的文件 */
export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico|.*\\..*).*)"],
};
