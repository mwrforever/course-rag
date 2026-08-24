"use client";

/**
 * 认证上下文（AuthProvider + useAuth，任务 7 核心）
 *
 * 职责（设计文档 §3.1/§1.5.6）：
 * - 挂载静默续期：AT 内存变量随页面刷新丢失，凭 localStorage 的 RT 调 refresh 恢复登录态
 *   （无 RT 时立即就绪；失败静默保持未登录，不打断首屏）
 * - login/logout 状态流转：经 api client 完成凭据落存，本层维护用户信息（登录响应缓存）
 * - 注册 401 刷新失败全局登出回调：api 层清完凭据后置空用户态（跳登录由 middleware/路由守卫承担）
 *
 * 线程安全说明：React 单向数据流内使用，无共享可变状态并发问题。
 */
import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import {
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

/** 认证上下文值：isLoading 仅覆盖挂载静默续期窗口 */
export interface AuthContextValue {
  user: AuthUser | null;
  accessToken: string | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login(username: string, password: string): Promise<void>;
  logout(): Promise<void>;
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

  // 挂载静默续期：有 RT 才尝试恢复（无 RT 直接就绪，不发无效请求）
  useEffect(() => {
    if (!getRefreshToken()) {
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

  // 注册 401 刷新失败登出回调：api 层清完凭据后同步置空用户态；卸载时注销防悬空
  useEffect(() => {
    setUnauthorizedHandler(() => {
      setUser(null);
      setToken(null);
    });
    return () => setUnauthorizedHandler(null);
  }, []);

  /** 登录：经 api client 落存凭据后置登录态（失败向上抛由登录页分级展示） */
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

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      accessToken,
      isAuthenticated: user !== null,
      isLoading,
      login,
      logout,
    }),
    [user, accessToken, isLoading, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

/** 认证上下文钩子：必须在 AuthProvider 内使用，误用快速失败 */
export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth 必须在 AuthProvider 内使用");
  }
  return context;
}
