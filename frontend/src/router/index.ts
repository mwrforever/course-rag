import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

import AdminLayout from '@/layouts/AdminLayout.vue'
import CourseDetailLayout from '@/views/course/CourseDetailLayout.vue'
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
/** 超管专属白名单（设计 §2.4.7：教师管理 + 会话审计 + 安全审计三页） */
const SUPER_ONLY: UserRole[] = ['SUPER_ADMIN']

/**
 * 受保护页面 meta 工厂：requiresAuth 显式声明（子路由独立可读，与父路由合并语义一致）
 *
 * @param title 页面标题（布局壳顶栏标题使用）
 * @param roles 允许访问的角色白名单
 */
function pageMeta(title: string, roles: UserRole[]) {
  return { title, requiresAuth: true, roles }
}

/**
 * B 端路由表（UI 重构 2026-08-25：职责拆分后的页面清单）
 *
 * - 公开路由仅 /login、/forbidden（403）、/404（未匹配兜底，替代静默重定向）
 * - 全部业务页面挂载在 AdminLayout 布局壳父路由下（深色侧栏 + 顶栏 + 内容区）
 * - 超管专属页（教师管理/会话审计/登录记录/Token 黑名单）meta.roles 仅 SUPER_ADMIN
 * - 课程详情拆子路由：概览/内容/排期/教师/学生（CourseEditView 1635 行单页按域拆分）
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
    path: '/404',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { title: '页面不存在', requiresAuth: false },
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
        meta: pageMeta('知识库管理', BOTH_ROLES),
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
        component: () => import('@/views/course/CourseOverviewView.vue'),
        meta: pageMeta('新建课程', BOTH_ROLES),
      },
      // 课程详情：壳布局承载子导航，五个领域独立子路由（职责拆分，替代 1635 行单页）
      {
        path: 'courses/:id',
        component: CourseDetailLayout,
        meta: { title: '编辑课程', requiresAuth: true, roles: BOTH_ROLES },
        children: [
          {
            path: '',
            name: 'course-detail',
            component: () => import('@/views/course/CourseOverviewView.vue'),
            meta: pageMeta('课程概览', BOTH_ROLES),
          },
          {
            path: 'content',
            name: 'course-content',
            component: () => import('@/views/course/CourseContentView.vue'),
            meta: pageMeta('课程内容', BOTH_ROLES),
          },
          {
            path: 'schedule',
            name: 'course-schedule',
            component: () => import('@/views/course/CourseScheduleView.vue'),
            meta: pageMeta('排期管理', BOTH_ROLES),
          },
          {
            path: 'teachers',
            name: 'course-teachers',
            component: () => import('@/views/course/CourseTeachersView.vue'),
            meta: pageMeta('教师分配', BOTH_ROLES),
          },
          {
            path: 'students',
            name: 'course-students',
            component: () => import('@/views/course/CourseStudentsView.vue'),
            meta: pageMeta('学生名单', BOTH_ROLES),
          },
        ],
      },
      {
        path: 'students',
        name: 'students',
        component: () => import('@/views/StudentsView.vue'),
        meta: pageMeta('学生管理', BOTH_ROLES),
      },
      {
        path: 'teachers',
        name: 'teachers',
        component: () => import('@/views/TeachersView.vue'),
        meta: pageMeta('教师管理', SUPER_ONLY),
      },
      // 旧 /users 页职责已拆分为学生/教师管理，保留重定向兼容历史入口
      {
        path: 'users',
        redirect: { name: 'students' },
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
    // 未匹配路由兜底 404 页（UI 重构：替代静默重定向仪表盘）
    path: '/:pathMatch(.*)*',
    redirect: '/404',
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

  /** 启动身份恢复是否已执行（M10：仅本路由实例首个导航前执行一次） */
  let bootstrapped = false

  /**
   * 启动身份恢复（M10）：刷新后 AT cookie 兜底放行请求 → 无 401 → 身份字段不恢复。
   * 首个路由解析前 await fetchMe（RT 在且 displayName 空时才发请求），失败静默；
   * 身份先于守卫裁决恢复，角色门禁可读到最新 role（不再依赖 role=null 放行兜底）。
   */
  async function ensureBootstrapped(): Promise<void> {
    if (bootstrapped) return
    bootstrapped = true
    const auth = useAuthStore()
    await auth.fetchMe()
  }

  /**
   * 全局前置守卫（设计 §3.1 B 端 beforeEach，三态）：
   *
   * 1. 公开路由：登录页放行；已登录访问登录页送回仪表盘
   * 2. 受保护路由：未登录 → 登录页并携带 redirect 回跳参数
   * 3. 角色门禁：meta.roles 白名单不匹配 → ForbiddenView（403 页）
   *
   * 边界说明：刷新页面后 AT 丢失、role=null（RT 仍在）时角色门禁放行，
   * 由 api 层静默刷新恢复角色或后端 403 兜底，避免刷新即被误挡
   * （M10 起首个导航前已先经 fetchMe 恢复身份，正常路径下 role 在场）。
   */
  router.beforeEach(async (to) => {
    // 启动恢复：仅首个导航前执行一次（await 保证身份先恢复再走三态裁决）
    await ensureBootstrapped()
    const auth = useAuthStore()

    // 公开路由：仅登录页/403 页/404 页；已登录访问登录页 → 仪表盘
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
