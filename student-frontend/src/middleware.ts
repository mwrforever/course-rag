import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

/**
 * C 端路由门卫（设计 §3.1 + 审核补记 G6；登录弹窗化 2026-08-26 修订；
 * 2026-08-27 用户拍板：仅首页未登录可访问；认证刷新链路修复 2026-08-30）
 *
 * 边界约束：middleware 运行于 edge runtime 无法验签 JWT，只做 cookie 存在性检查
 * （不解析 token 内容）；真实鉴权一律以 API 401 全局拦截（api client 单飞刷新）为准。
 *
 * 规则：
 * - 公开路由：/（首页）、/login（独立登录页）——未登录可直接访问
 * - 受保护路由（/courses /chat /profile）：AT cookie 缺失但 c_rt_live 提示 cookie 存在
 *   → 放行（AT 过期 + RT 有效的静默续期窗口，客户端 AuthProvider 原地续期接管，
 *   避免闪登录页；RT 无效再由续期失败弹登录窗兜底）；两者皆无 → /login?next=<原路径>
 *   （登录成功后按 next 白名单站内回跳）
 * - 首页推荐课程继续走 /api/v1/public/courses（后端公开端点，不受本门控影响）
 */
const AUTH_COOKIE = "commerce_token";
/** RT 存在性提示 cookie（api 层写/清；真匿名者无此 cookie，仍走登录页） */
const RT_LIVE_COOKIE = "c_rt_live";
/** 受保护路由前缀（课程中心/课程对话/个人中心——仅首页公开，2026-08-27 用户拍板） */
const PROTECTED_PREFIXES = ["/courses", "/chat", "/profile"];

/** 判定是否受保护路由（前缀匹配） */
function isProtected(pathname: string): boolean {
  return PROTECTED_PREFIXES.some((prefix) => pathname.startsWith(prefix));
}

/** 门卫逻辑：cookie 存在性检查 + 重定向 */
export function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const hasAuthCookie = request.cookies.has(AUTH_COOKIE);
  // 持有凭证迹象：AT cookie 或 RT 存在性提示 cookie 任一存在（纯存在性检查，不解析内容）
  const hasCredentials = hasAuthCookie || request.cookies.has(RT_LIVE_COOKIE);

  if (!hasCredentials && isProtected(pathname)) {
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
