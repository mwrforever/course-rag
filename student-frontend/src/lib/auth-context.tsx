"use client";

/**
 * 认证上下文（AuthProvider + useAuth，任务 7 核心；登录弹窗化 2026-08-26 修订）
 *
 * 职责（设计文档 §3.1/§1.5.6）：
 * - 挂载静默续期：AT 内存变量随页面刷新丢失，凭 localStorage 的 RT 调 refresh 恢复登录态
 *   （无 RT 时立即就绪；失败静默保持未登录，不打断首屏）
 * - login/logout 状态流转：经 api client 完成凭据落存，本层维护用户信息（登录响应缓存）
 * - 注册 401 刷新失败全局登出回调：api 层清完凭据后置空用户态，打开登录弹窗（不再整页跳
 *   /login，登录弹窗全路由可触发）+ 展示轻量自制 toast「登录已失效，请重新登录」
 * - 登录弹窗全局状态：openLoginDialog({ afterLogin }) 供任意组件登记登录成功后的后续动作
 *   （如继续提问/进入详情页），submitLogin 成功后自动关闭弹窗并执行登记动作
 *
 * 线程安全说明：React 单向数据流内使用，无共享可变状态并发问题。
 */
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import {
  clearRtLiveCookie,
  getRefreshToken,
  login as apiLogin,
  logout as apiLogout,
  refresh as apiRefresh,
  setUnauthorizedHandler,
} from "./api";
import type { LoginResponse } from "./types";

/** 登录用户信息（登录/刷新响应缓存，设计 §1.5.6 个人中心展示用） */
export interface AuthUser {
  userId: string;
  role: string;
  displayName: string;
}

/** 登录弹窗打开选项：登录成功后的后续动作（可选） */
export interface LoginDialogOptions {
  afterLogin?: () => void | Promise<void>;
}

/** 认证上下文值：isLoading 仅覆盖挂载静默续期窗口 */
export interface AuthContextValue {
  user: AuthUser | null;
  accessToken: string | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login(username: string, password: string): Promise<void>;
  logout(): Promise<void>;
  /** 登录弹窗展开态（LoginDialog 渲染依据） */
  loginDialogOpen: boolean;
  /** 打开登录弹窗并登记登录成功后的后续动作 */
  openLoginDialog(options?: LoginDialogOptions): void;
  /** 关闭登录弹窗（丢弃未执行的 afterLogin） */
  closeLoginDialog(): void;
  /** 登录并自动关闭弹窗 + 执行 afterLogin（失败向上抛由弹窗分级展示） */
  submitLogin(username: string, password: string): Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/** LoginResponse → 裁剪为前端持有的用户信息（不落 token） */
function toUser(response: LoginResponse): AuthUser {
  return { userId: response.userId, role: response.role, displayName: response.displayName };
}

/**
 * 认证 Provider：挂载于根 layout，包裹全部路由
 *
 * @param children 子路由内容
 */
export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [accessToken, setToken] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  // 登录失效轻提示开关：认证过期（401 刷新失败）时置真，toast 展示 4 秒后自动消失
  const [sessionExpired, setSessionExpired] = useState(false);
  // 登录弹窗展开态 + 登录成功后的后续动作（ref 持有避免触发重渲染）
  const [loginDialogOpen, setLoginDialogOpen] = useState(false);
  const afterLoginRef = useRef<(() => void | Promise<void>) | null>(null);

  // 挂载静默续期：有 RT 才尝试恢复（无 RT 直接就绪，不发无效请求）
  useEffect(() => {
    if (!getRefreshToken()) {
      // 收口残留提示 cookie：localStorage 无 RT 但 c_rt_live 残留（用户手清存储/ITP 分区）时，
      // 常规清理路径（setRefreshToken/clearCredentials）不触发；不清会让 middleware 放行真匿名者
      //（受保护页渲染骨架→登录弹窗），清掉后下次导航回归真匿名 307 语义
      clearRtLiveCookie();
      setIsLoading(false);
      return;
    }
    let cancelled = false;
    apiRefresh()
      .then((response) => {
        if (cancelled) {
          return;
        }
        setUser(toUser(response));
        setToken(response.accessToken);
      })
      .catch(() => {
        // 静默失败：保持未登录即止（api 层已按需清凭据/触发回调），不打断首屏
      })
      .finally(() => {
        if (!cancelled) {
          setIsLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // 注册 401 刷新失败登出回调：api 层清完凭据后置空用户态 + 打开登录弹窗（登录弹窗化：
  // 不再跳转 /login，弹窗在任意页面原位展开，登录后回跳能力由调用方经 afterLogin 登记）；
  // 卸载时注销防悬空
  useEffect(() => {
    setUnauthorizedHandler(() => {
      setUser(null);
      setToken(null);
      // 登录失效闭环：toast 提示 + 打开登录弹窗
      setSessionExpired(true);
      setLoginDialogOpen(true);
    });
    return () => setUnauthorizedHandler(null);
  }, []);

  // 登录失效 toast 自动消失（4 秒），卸载时清除定时器
  useEffect(() => {
    if (!sessionExpired) {
      return;
    }
    const timer = window.setTimeout(() => setSessionExpired(false), 4000);
    return () => window.clearTimeout(timer);
  }, [sessionExpired]);

  /** 登录：经 api client 落存凭据后置登录态（失败向上抛由登录弹窗分级展示） */
  const login = useCallback(async (username: string, password: string) => {
    const response = await apiLogin(username, password);
    setUser(toUser(response));
    setToken(response.accessToken);
  }, []);

  /** 登出：后端吊销尽力而为 + 清空本地登录态（凭据由 api 层清理） */
  const logout = useCallback(async () => {
    await apiLogout();
    setUser(null);
    setToken(null);
  }, []);

  /** 打开登录弹窗并登记后续动作（被 401 回调与其他调用方共用） */
  const openLoginDialog = useCallback((options?: LoginDialogOptions) => {
    afterLoginRef.current = options?.afterLogin ?? null;
    setLoginDialogOpen(true);
  }, []);

  /** 关闭登录弹窗：丢弃未执行的 afterLogin（用户主动取消场景不执行后续动作） */
  const closeLoginDialog = useCallback(() => {
    afterLoginRef.current = null;
    setLoginDialogOpen(false);
  }, []);

  /** 弹窗提交登录：成功后关闭弹窗并执行登记动作；动作失败不阻断登录态（由登记方自管） */
  const submitLogin = useCallback(
    async (username: string, password: string) => {
      await login(username, password);
      setLoginDialogOpen(false);
      const afterLogin = afterLoginRef.current;
      afterLoginRef.current = null;
      if (afterLogin) {
        try {
          await afterLogin();
        } catch {
          // afterLogin 为调用方登记的附带动作（跳转/刷新等），失败不撤销登录态
        }
      }
    },
    [login],
  );

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      accessToken,
      isAuthenticated: user !== null,
      isLoading,
      login,
      logout,
      loginDialogOpen,
      openLoginDialog,
      closeLoginDialog,
      submitLogin,
    }),
    [
      user,
      accessToken,
      isLoading,
      login,
      logout,
      loginDialogOpen,
      openLoginDialog,
      closeLoginDialog,
      submitLogin,
    ],
  );

  return (
    <AuthContext.Provider value={value}>
      {children}
      {sessionExpired ? (
        // 登录失效轻量自制 toast（无新依赖）：fixed 底部居中，覆盖全部路由；role=alert 供读屏即时播报
        <div
          role="alert"
          className="fixed bottom-6 left-1/2 z-50 -translate-x-1/2 rounded-xl border border-danger/30 bg-danger px-4 py-2.5 text-sm text-white shadow-lg"
        >
          登录已失效，请重新登录
        </div>
      ) : null}
    </AuthContext.Provider>
  );
}

/** 认证上下文钩子：必须在 AuthProvider 内使用，误用快速失败 */
export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth 必须在 AuthProvider 内使用");
  }
  return context;
}
