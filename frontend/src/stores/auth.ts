import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { ApiError, authApi } from '@/lib/api'

import type { LoginResponse, UserRole } from '@/lib/types'

/** B 端 RT 的 sessionStorage 键（管理端安全要求更高，关浏览器即清；区别于 C 端 localStorage） */
export const REFRESH_TOKEN_KEY = 'b_rt'

/** 管理后台允许进入的角色白名单（设计 §3.1：登录后校验 role ∈ {TEACHER, SUPER_ADMIN}） */
const ADMIN_ROLES: readonly UserRole[] = ['TEACHER', 'SUPER_ADMIN']

/**
 * 认证状态仓库（Pinia 单例）
 *
 * 管理端凭据存储策略（设计 §3.1）：AT 仅内存（后端同时写 httpOnly cookie 兜底通道），
 * RT 存 sessionStorage（key `b_rt`，关浏览器即清）。刷新页面后 AT 丢失、RT 仍在：
 * isAuthenticated 保持 true，由 api 层首次 401 静默 refresh 恢复完整登录态。
 *
 * 线程安全注意：refreshOnce 模块级单飞 promise 由主线程事件循环调度，无并发竞争；
 * 并发 401 场景（api 拦截器）共享同一 promise 保证仅发一次 refresh 请求。
 */
export const useAuthStore = defineStore('auth', () => {
  // Access Token：仅内存持有，不落任何持久化存储（cookie 为后端兜底通道）
  const accessToken = ref<string | null>(null)
  // Refresh Token：sessionStorage 持久化（会话级），初始化时从存储恢复
  const refreshToken = ref<string | null>(sessionStorage.getItem(REFRESH_TOKEN_KEY))
  const userId = ref<string | null>(null)
  const role = ref<UserRole | null>(null)
  const displayName = ref<string | null>(null)

  /** 单飞刷新共享 promise：并发 401 只发一次 refresh（api 拦截器复用此入口） */
  let refreshing: Promise<LoginResponse> | null = null

  /** 登录态判定：AT 或 RT 任一存在即视为已登录（AT 丢失可由静默刷新恢复） */
  const isAuthenticated = computed(() => Boolean(accessToken.value) || Boolean(refreshToken.value))

  /**
   * 写入登录态（登录/静默刷新成功后调用）
   *
   * @param payload 登录接口响应：AT/RT/userId/role/displayName（userId 为 string，Long 序列化铁律）
   */
  function setAuth(payload: LoginResponse) {
    accessToken.value = payload.accessToken
    refreshToken.value = payload.refreshToken
    // RT 落 sessionStorage（空 RT 视为无：仅内存 AT 场景不写脏值）
    if (payload.refreshToken) {
      sessionStorage.setItem(REFRESH_TOKEN_KEY, payload.refreshToken)
    } else {
      sessionStorage.removeItem(REFRESH_TOKEN_KEY)
    }
    userId.value = payload.userId
    role.value = payload.role
    displayName.value = payload.displayName
  }

  /** 清空登录态（登出/refresh 失败全量吊销时调用），同步清理 sessionStorage */
  function clearAuth() {
    accessToken.value = null
    refreshToken.value = null
    sessionStorage.removeItem(REFRESH_TOKEN_KEY)
    userId.value = null
    role.value = null
    displayName.value = null
  }

  /**
   * 登录（用户名 + 密码）
   *
   * 流程：调 login 接口 → 管理角色白名单校验（STUDENT 等角色不入后台，提示无权限不跳转）
   * → 写入登录态。接口异常（401 用户名或密码错误等）原样上抛，由登录页分级展示。
   *
   * @param username 用户名（非邮箱，来源用户输入）
   * @param password 登录密码（来源用户输入，不落日志）
   * @returns 登录响应（含 AT/RT 与用户信息）
   * @throws ApiError 接口失败（401/403/503/网络）或角色无权限（403 固定文案）
   */
  async function login(username: string, password: string): Promise<LoginResponse> {
    const payload = await authApi.login({ username, password, deviceType: 'WEB_DESKTOP' })
    // 角色门禁：非管理角色不落任何凭据，提示无权限（登录页 Alert 展示，不跳转）
    if (!ADMIN_ROLES.includes(payload.role)) {
      clearAuth()
      throw new ApiError(403, '当前账号无管理后台访问权限', 403)
    }
    setAuth(payload)
    return payload
  }

  /**
   * 登出：调登出接口（幂等）后清本地凭据
   *
   * 登出接口异常不阻塞清理（后端吊销失败时 RT 7d 时效兜底），保证前端登出流始终可达。
   */
  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } catch {
      // 幂等容错：接口失败（网络/令牌已失效）仍继续本地清理
    }
    clearAuth()
  }

  /**
   * 静默刷新（单飞）：并发调用共享同一 promise，成功后旋转 RT 并回写登录态
   *
   * @returns 新登录响应（AT/RT 已写入 store 与 sessionStorage）
   * @throws ApiError 无 RT（未登录）或 refresh 接口失败；失败不清本地凭据，
   *         由 api 层拦截器统一执行「清凭据 → 跳登录 → toast」失败登出流
   */
  function refreshOnce(): Promise<LoginResponse> {
    if (!refreshToken.value) {
      return Promise.reject(new ApiError(401, '未登录，无法刷新', 401))
    }
    if (!refreshing) {
      refreshing = authApi
        .refresh({ refreshToken: refreshToken.value })
        .then((payload) => {
          // 刷新成功：旋转 RT（一次性），回写内存与 sessionStorage
          setAuth(payload)
          return payload
        })
        .finally(() => {
          // 无论成败均释放单飞引用：下次 401 可重新发起
          refreshing = null
        })
    }
    return refreshing
  }

  return {
    accessToken,
    refreshToken,
    userId,
    role,
    displayName,
    isAuthenticated,
    setAuth,
    clearAuth,
    login,
    logout,
    refreshOnce,
  }
})
