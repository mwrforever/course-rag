import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

import type { UserRole } from '@/lib/types'

/**
 * 路由 meta 类型增强：title 页面标题 / requiresAuth 是否需要登录 / roles 允许访问的角色
 */
declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    requiresAuth?: boolean
    roles?: UserRole[]
  }
}

/**
 * B 端路由表（骨架）
 *
 * 公开路由仅 /login；其余路由标记 requiresAuth 与 roles，由 beforeEach 守卫统一鉴权
 * （设计 §2.1/§3.1：B 端路由守卫走 Pinia 登录态，替代 Next middleware）。
 * 知识库/文档/分片/课程/用户/反馈/会话/安全审计等业务路由随后续任务逐个补入本表。
 */
export const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', requiresAuth: false },
  },
  {
    path: '/',
    redirect: '/dashboard',
  },
  {
    path: '/dashboard',
    name: 'dashboard',
    component: () => import('@/views/DashboardView.vue'),
    meta: { title: '仪表盘', requiresAuth: true, roles: ['TEACHER', 'SUPER_ADMIN'] },
  },
  {
    // 未匹配路由兜底回仪表盘；独立 404 页随后续任务决定是否需要
    path: '/:pathMatch(.*)*',
    redirect: '/dashboard',
  },
]

/**
 * 创建应用路由器实例（工厂方法：测试可独立创建实例，避免跨用例共享路由状态）
 */
export function createAppRouter() {
  const router = createRouter({
    history: createWebHistory(import.meta.env.BASE_URL),
    routes,
  })

  /**
   * 全局前置守卫（鉴权骨架）
   *
   * 1. 受保护路由未登录 → 跳登录页并携带 redirect 回跳参数
   * 2. 已登录访问登录页 → 回仪表盘
   * 3. meta.roles 角色校验随角色页面上线后在此补算法（当前仅 TEACHER/SUPER_ADMIN 两类角色）
   */
  router.beforeEach((to) => {
    const auth = useAuthStore()
    if (to.meta.requiresAuth && !auth.isAuthenticated) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }
    if (to.name === 'login' && auth.isAuthenticated) {
      return { name: 'dashboard' }
    }
    return true
  })

  return router
}

// 应用单例：main.ts 与组件内使用的默认实例
const router = createAppRouter()

export default router
