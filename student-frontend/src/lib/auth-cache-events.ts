/**
 * 账号切换缓存清理事件总线（BUG-06 修复）
 *
 * 背景：AuthProvider 挂根布局、QueryProvider 挂 (main)/(chat) 两处路由组布局——
 * auth-context 层级取不到 QueryClient，401 全局登出（弹窗换登）与登录成功两条
 * 路径无法直接清空 React Query 缓存；旧账号的 ["my-courses"]/
 * ["chat-sidebar-sessions"]/["session-messages", *] 等缓存（staleTime 30s +
 * refetchOnWindowFocus:false，挂载组件无重拉触发点）在换登后持续向新账号展示，
 * 造成跨账号数据串号。
 *
 * 职责：模块级监听器注册表——auth-context 在「401 强制登出回调」「登录成功」
 * 两处广播账号切换事件，QueryProvider 挂载订阅并清空 QueryClient 缓存，
 * 行为与三处显式登出（site-header / chat-sidebar / profile 的 queryClient.clear()）对齐。
 *
 * 线程安全：单线程 DOM 环境下使用；监听器仅增删于组件挂载周期，广播期间无并发修改。
 */

/** 账号切换监听器（QueryProvider 注册：收到即清空缓存） */
type AuthCacheResetListener = () => void;

/** 监听器注册表（Set 保证同一监听器幂等注册一次） */
const listeners = new Set<AuthCacheResetListener>();

/**
 * 订阅账号切换事件（QueryProvider 挂载时调用）
 *
 * @param listener 收到事件的回调（清空各自路由组 QueryClient 的缓存）
 * @returns 退订函数（useEffect 清理调用，防卸载后悬空回调泄漏）
 */
export function subscribeAuthCacheReset(listener: AuthCacheResetListener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/**
 * 广播账号切换（auth-context 侧调用）
 *
 * 触发点：① 401 刷新失败全局登出回调（用户态清空，缓存同步清空）；
 * ② 登录成功（login/submitLogin——含未过期主动换登场景，旧账号缓存不残留）。
 * 无监听器时（(auth) 登录页等未挂 QueryProvider 的路由）为空操作，无缓存可清。
 */
export function emitAuthCacheReset(): void {
  for (const listener of listeners) {
    listener();
  }
}
