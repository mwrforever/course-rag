import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

import AdminLayout from '@/layouts/AdminLayout.vue'
import { useAuthStore } from '@/stores/auth'

import type { UserRole } from '@/lib/types'

/** 路由 meta 类型增强：title 页面标题 / requiresAuth 是否需登录 / roles 允许访问的角色白名单 */
declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    requiresAuth?: boolean
    roles?: UserRole[]
  }
}

/** 两角色白名单（设计 §2.4：默认全部业务页两角色可进，教师限己建由后端约束） */
const BOTH_ROLES: UserRole[] = ['TEACHER', 'SUPER_ADMIN']
/** 超管专属白名单（设计 §2.4.7：会话审计 + 安全审计两页） */
const SUPER_ONLY: UserRole[] = ['SUPER_ADMIN']

/**
 * 受保护页面 meta 工厂：requiresAuth 显式声明（子路由独立可读，与父路由合并语义一致）
 *
 * @param title 页面标题（布局壳页头 H1 使用）
 * @param roles 允许访问的角色白名单
 */
function pageMeta(title: string, roles: UserRole[]) {
  return { title, requiresAuth: true, roles }
}

/**
 * B 端路由表（设计 §2.4 页面清单全量落地）
 *
 * - 公开路由仅 /login 与 /forbidden（403 页）
 * - 全部业务页面挂载在 AdminLayout 布局壳父路由下（顶栏+侧导航+内容区）
 * - 超管专属页（会话审计/登录记录/Token 黑名单）meta.roles 仅 SUPER_ADMIN
 * - 未实现页面使用占位 view 组件，页面实现随后续任务逐个填充
 */
export const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', requiresAuth: false },
  },
  {
    path: '/forbidden',
    name: 'forbidden',
    component: () => import('@/views/ForbiddenView.vue'),
    meta: { title: '无权访问', requiresAuth: false },
  },
  {
    path: '/',
    name: 'admin-layout',
    component: AdminLayout,
    meta: { requiresAuth: true },
    children: [
      // 空 path 子路由显式命名（消除 vue-router 未命名子路由告警；重定向兜底进仪表盘）
      { path: '', name: 'admin-home', redirect: '/dashboard' },
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/DashboardView.vue'),
        meta: pageMeta('仪表盘', BOTH_ROLES),
      },
      {
        path: 'knowledge-bases',
        name: 'knowledge-bases',
        component: () => import('@/views/KnowledgeBasesView.vue'),
        meta: pageMeta('知识库', BOTH_ROLES),
      },
      {
        path: 'knowledge/documents',
        name: 'knowledge-documents',
        component: () => import('@/views/DocumentsView.vue'),
        meta: pageMeta('文档管理', BOTH_ROLES),
      },
      {
        path: 'knowledge/documents/:id',
        name: 'knowledge-document-detail',
        component: () => import('@/views/DocumentDetailView.vue'),
        meta: pageMeta('文档详情', BOTH_ROLES),
      },
      {
        path: 'knowledge/chunks',
        name: 'knowledge-chunks',
        component: () => import('@/views/ChunksView.vue'),
        meta: pageMeta('分片修正', BOTH_ROLES),
      },
      {
        path: 'courses',
        name: 'courses',
        component: () => import('@/views/CoursesView.vue'),
        meta: pageMeta('课程管理', BOTH_ROLES),
      },
      {
        path: 'courses/new',
        name: 'course-new',
        component: () => import('@/views/CourseEditView.vue'),
        meta: pageMeta('新建课程', BOTH_ROLES),
      },
      {
        path: 'courses/:id',
        name: 'course-detail',
        component: () => import('@/views/CourseEditView.vue'),
        meta: pageMeta('编辑课程', BOTH_ROLES),
      },
      {
        path: 'users',
        name: 'users',
        component: () => import('@/views/UsersView.vue'),
        meta: pageMeta('用户管理', BOTH_ROLES),
      },
      {
        path: 'feedback',
        name: 'feedback',
        component: () => import('@/views/FeedbackView.vue'),
        meta: pageMeta('反馈报表', BOTH_ROLES),
      },
      {
        path: 'sessions',
        name: 'sessions',
        component: () => import('@/views/SessionsAdminView.vue'),
        meta: pageMeta('会话审计', SUPER_ONLY),
      },
      {
        path: 'security/login-records',
        name: 'login-records',
        component: () => import('@/views/LoginRecordsView.vue'),
        meta: pageMeta('登录记录', SUPER_ONLY),
      },
      {
        path: 'security/token-blacklist',
        name: 'token-blacklist',
        component: () => import('@/views/TokenBlacklistView.vue'),
        meta: pageMeta('Token 黑名单', SUPER_ONLY),
      },
    ],
  },
  {
    // 未匹配路由兜底回仪表盘（独立 404 页不在此任务范围）
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
   * 全局前置守卫（设计 §3.1 B 端 beforeEach，三态）：
   *
   * 1. 公开路由：登录页放行；已登录访问登录页送回仪表盘
   * 2. 受保护路由：未登录 → 登录页并携带 redirect 回跳参数
   * 3. 角色门禁：meta.roles 白名单不匹配 → ForbiddenView（403 页）
   *
   * 边界说明：刷新页面后 AT 丢失、role=null（RT 仍在）时角色门禁放行，
   * 由 api 层静默刷新恢复角色或后端 403 兜底，避免刷新即被误挡。
   */
  router.beforeEach((to) => {
    const auth = useAuthStore()

    // 公开路由：仅登录页/403 页；已登录访问登录页 → 仪表盘
    if (!to.meta.requiresAuth) {
      if (to.name === 'login' && auth.isAuthenticated) {
        return { name: 'dashboard' }
      }
      return true
    }

    // 受保护路由：未登录 → 登录页并携带 redirect 回跳参数
    if (!auth.isAuthenticated) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }

    // 角色门禁：白名单不匹配 → ForbiddenView（role=null 为登录态未恢复，放行见类注释）
    if (to.meta.roles && auth.role && !to.meta.roles.includes(auth.role)) {
      return { name: 'forbidden' }
    }

    return true
  })

  return router
}

// 应用单例：main.ts 与组件（api 层失败登出跳转）使用的默认实例
const router = createAppRouter()

export default router
