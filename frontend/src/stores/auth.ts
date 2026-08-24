import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import type { LoginResponse, UserRole } from '@/lib/types'

/**
 * 认证状态仓库（Pinia）
 *
 * 管理端凭据存储策略（设计 §3.1）：AT 仅内存（后端同时写 httpOnly cookie 兜底），
 * RT 存 sessionStorage（关浏览器即清，管理端安全要求更高）。
 * 刷新页面后 AT 丢失、RT 仍在：isAuthenticated 保持 true，由后续 api 层静默 refresh 恢复。
 */
export const useAuthStore = defineStore('auth', () => {
  // B 端 RT 的 sessionStorage 键（带端前缀，避免与 C 端 localStorage 键冲突）
  const REFRESH_TOKEN_KEY = 'b_fe_refresh_token'

  // Access Token：仅内存持有，不落任何持久化存储
  const accessToken = ref<string | null>(null)
  // Refresh Token：sessionStorage 持久化（会话级），初始化时从存储恢复
  const refreshToken = ref<string | null>(sessionStorage.getItem(REFRESH_TOKEN_KEY))
  const userId = ref<string | null>(null)
  const role = ref<UserRole | null>(null)
  const displayName = ref<string | null>(null)

  /** 登录态判定：AT 或 RT 任一存在即视为已登录（AT 丢失可由静默刷新恢复） */
  const isAuthenticated = computed(() => accessToken.value !== null || refreshToken.value !== null)

  /**
   * 写入登录态（登录/静默刷新成功后调用）
   *
   * @param payload 登录接口响应：AT/RT/userId/role/displayName（userId 为 string，Long 序列化铁律）
   */
  function setAuth(payload: LoginResponse) {
    accessToken.value = payload.accessToken
    refreshToken.value = payload.refreshToken
    sessionStorage.setItem(REFRESH_TOKEN_KEY, payload.refreshToken)
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

  return {
    accessToken,
    refreshToken,
    userId,
    role,
    displayName,
    isAuthenticated,
    setAuth,
    clearAuth,
  }
})
