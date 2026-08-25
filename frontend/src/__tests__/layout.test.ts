import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import AdminLayout, {
  createNavGroups,
  isGroupActive,
  navGroups,
  resolvePageKey,
} from '@/layouts/AdminLayout.vue'
import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'

import type { UserRole } from '@/lib/types'

/**
 * 布局壳测试（Task 16 核心 + UI 重构 2026-08-25 深色侧栏）
 *
 * 覆盖契约：
 * 1. createNavGroups 双层角色过滤：组级（审计仅超管）+ 项级（教师管理仅超管），空组剔除
 * 2. 深色侧栏：图标分组渲染（单子项直链/多子项可展开默认态）、折叠切换持久化、
 *    分组展开持久化、localStorage 异常降级
 * 3. 顶栏面包屑（分组/页面标题）与用户下拉（Esc/外点关闭 + 退出登录）
 * 4. 路由切换过渡容器与内容区统一限宽
 */

/** 课程接口调用记录（vi.fn 供「壳不重挂载」回归用例断言取数次数） */
const courseApiMock = vi.hoisted(() => ({
  get: vi.fn(),
  contents: vi.fn(),
}))

/** 仪表盘接口 mock：稳定数据（避免真实 axios 网络调用） */
vi.mock('@/lib/api', () => ({
  ApiError: class ApiError extends Error {},
  dashboardApi: {
    stats: () =>
      Promise.resolve({
        documentCount: '12',
        pendingChunkCount: '3',
        knowledgeBaseCount: '4',
      }),
    feedbackStats: () =>
      Promise.resolve({ studentCount: '30', feedbackCount: '90', likeRate: 0.5 }),
    feedbackTrend: () => Promise.resolve([]),
  },
  documentApi: {
    list: () => Promise.resolve({ records: [], total: '0', page: 1, size: 5 }),
  },
  courseApi: courseApiMock,
}))

/** 写入指定角色的登录态 */
function setLoginRole(role: UserRole) {
  useAuthStore().setAuth({
    accessToken: 'at-1',
    refreshToken: 'rt-1',
    userId: '1001',
    role,
    displayName: role === 'SUPER_ADMIN' ? '李超管' : '张老师',
  })
}

describe('createNavGroups：双层角色过滤', () => {
  it('TEACHER：隐藏审计分组与学员分组中的教师管理项', () => {
    const groups = createNavGroups('TEACHER')
    const labels = groups.flatMap((g) => [g.label, ...g.items.map((i) => i.label)])

    expect(labels).toContain('仪表盘')
    expect(labels).toContain('知识库')
    expect(labels).toContain('知识库管理')
    expect(labels).toContain('文档')
    expect(labels).toContain('分片')
    expect(labels).toContain('课程')
    expect(labels).toContain('学员')
    expect(labels).toContain('学生管理')
    expect(labels).toContain('反馈')
    // 项级过滤：教师管理仅超管；组级过滤：审计分组整体隐藏
    expect(labels).not.toContain('教师管理')
    expect(labels).not.toContain('审计')
    expect(labels).not.toContain('会话审计')
  })

  it('SUPER_ADMIN：全部 6 个分组可见（含教师管理与审计四项）', () => {
    const groups = createNavGroups('SUPER_ADMIN')
    const labels = groups.flatMap((g) => [g.label, ...g.items.map((i) => i.label)])

    expect(labels).toContain('教师管理')
    expect(labels).toContain('审计')
    expect(labels).toContain('会话审计')
    expect(labels).toContain('登录记录')
    expect(labels).toContain('Token 黑名单')
    expect(groups).toHaveLength(6)
  })

  it('登录态未恢复（role 为 null）：按最低权限渲染，超管分组/教师管理不可见', () => {
    const groups = createNavGroups(null)
    const labels = groups.flatMap((g) => [g.label, ...g.items.map((i) => i.label)])

    expect(labels).not.toContain('审计')
    expect(labels).not.toContain('教师管理')
    expect(createNavGroups(null)).toEqual(createNavGroups('TEACHER'))
  })

  it('导航静态结构与路由路径对应（供路由表与布局一致性校验）', () => {
    expect(navGroups).toHaveLength(6)
    const allTos = navGroups.flatMap((g) => g.items.map((i) => i.to))
    expect(allTos).toEqual([
      '/dashboard',
      '/knowledge-bases',
      '/knowledge/documents',
      '/knowledge/chunks',
      '/courses',
      '/students',
      '/teachers',
      '/feedback',
      '/sessions',
      '/security/login-records',
      '/security/token-blacklist',
    ])
  })

  it('isGroupActive：精确匹配与子路径前缀匹配，仪表盘不做前缀匹配', () => {
    expect(isGroupActive('/dashboard', '/dashboard')).toBe(true)
    expect(isGroupActive('/courses', '/dashboard')).toBe(false)
    // 子路径前缀：文档详情高亮「文档」
    expect(isGroupActive('/knowledge/documents/d-1', '/knowledge/documents')).toBe(true)
    // 课程详情五子路由高亮「课程管理」
    expect(isGroupActive('/courses/c-1/content', '/courses')).toBe(true)
  })
})

describe('resolvePageKey：页面级过渡身份键（评审修复 I1）', () => {
  const courseRoute = {
    matched: [{ path: '/' }, { path: 'courses/:id' }],
    params: { id: '1' } as Record<string, string | string[]>,
    path: '/courses/1',
  }

  it('同实体子路由切换：键不变（页面壳与 Transition 存活）', () => {
    const sub = { ...courseRoute, path: '/courses/1/content' }
    expect(resolvePageKey(sub)).toBe(resolvePageKey(courseRoute))
  })

  it('跨实体导航（路径参数变化）：键变化（重挂载重新取数，壳免 watch 参数）', () => {
    const other = {
      ...courseRoute,
      params: { id: '2' } as Record<string, string | string[]>,
      path: '/courses/2',
    }
    expect(resolvePageKey(other)).not.toBe(resolvePageKey(courseRoute))
  })

  it('跨页导航互异；matched 不足时回退完整 path 定义', () => {
    const students = {
      matched: [{ path: '/' }, { path: 'students' }],
      params: {} as Record<string, string | string[]>,
      path: '/students',
    }
    const orphan = { matched: [], params: {} as Record<string, string | string[]>, path: '/x' }
    expect(resolvePageKey(students)).not.toBe(resolvePageKey(courseRoute))
    expect(resolvePageKey(orphan)).toBe('/x|{}')
  })
})

describe('AdminLayout 渲染（深色侧栏）', () => {
  beforeEach(() => {
    sessionStorage.clear()
    window.localStorage.clear()
    vi.restoreAllMocks()
  })

  // RouterView 壳：模拟真实 App 挂载（深度 0 → 布局 → 深度 1 → 页面）。
  // 直接 mount AdminLayout 时 RouterView 深度为 0 会再渲染 matched[0]（布局自身），
  // 造成测试环境双层布局（2026-08-25 实证），真实应用经 App.vue 无此问题。
  const Shell = defineComponent(() => () => h(RouterView))

  async function mountLayout(role: UserRole, initialPath = '/dashboard') {
    const pinia = createPinia()
    setActivePinia(pinia)
    setLoginRole(role)
    const router = createAppRouter()
    await router.push(initialPath)
    await router.isReady()
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const wrapper = mount(Shell, {
      global: { plugins: [pinia, router, [VueQueryPlugin, { queryClient }]] },
    })
    return { wrapper, router, pinia }
  }

  it('TEACHER：深色侧栏渲染基础分组，无超管导航；内容区统一限宽', async () => {
    const { wrapper } = await mountLayout('TEACHER')

    // 侧栏：深色 ink 底 + 60 宽（w-60 展开态）
    const aside = wrapper.find('[data-testid="admin-sidebar"]')
    expect(aside.classes()).toContain('w-60')
    expect(aside.classes()).toContain('from-ink-950')
    // 品牌名与基础导航
    expect(wrapper.text()).toContain('课程助手管理后台')
    expect(wrapper.text()).toContain('仪表盘')
    expect(wrapper.text()).toContain('知识库')
    expect(wrapper.text()).toContain('学生管理')
    expect(wrapper.text()).toContain('反馈')
    // 超管可见项不可见
    expect(wrapper.text()).not.toContain('教师管理')
    expect(wrapper.text()).not.toContain('审计')
    // 全局 1400px 内容容器（非视图层重复包裹）
    expect(wrapper.find('.max-w-\\[1400px\\]').exists()).toBe(true)
    await vi.waitFor(() => expect(wrapper.text()).toContain('文档总数'))
    wrapper.unmount()
  })

  it('多子项分组：默认展开渲染子项；折叠态仅标题按钮', async () => {
    const { wrapper } = await mountLayout('SUPER_ADMIN', '/knowledge/documents')

    // 知识库分组默认展开：三个子项在场
    expect(wrapper.text()).toContain('知识库管理')
    expect(wrapper.text()).toContain('文档')
    expect(wrapper.text()).toContain('分片')
    // 审计分组 defaultOpen=false：初始折叠，点击展开出现子项
    const toggles = wrapper.findAll('[data-testid="nav-group-toggle"]')
    const auditToggle = toggles.find((t) => t.text().includes('审计'))
    expect(auditToggle?.attributes('aria-expanded')).toBe('false')
    expect(wrapper.text()).not.toContain('会话审计')
    await auditToggle?.trigger('click')
    expect(wrapper.text()).toContain('会话审计')
    expect(auditToggle?.attributes('aria-expanded')).toBe('true')
    wrapper.unmount()
  })

  it('折叠侧栏：切 w-16 图标态并持久化 localStroage；展开恢复', async () => {
    const { wrapper } = await mountLayout('TEACHER')

    await wrapper.find('button[aria-label="收起侧栏"]').trigger('click')
    expect(wrapper.find('[data-testid="admin-sidebar"]').classes()).toContain('w-16')
    expect(window.localStorage.getItem('cc.admin-sidebar.collapsed')).toBe('1')
    // 折叠态：品牌名与分组标题隐藏，展开按钮在场
    expect(wrapper.text()).not.toContain('课程助手管理后台')
    await wrapper.find('button[aria-label="展开侧栏"]').trigger('click')
    expect(wrapper.find('[data-testid="admin-sidebar"]').classes()).toContain('w-60')
    expect(window.localStorage.getItem('cc.admin-sidebar.collapsed')).toBe('0')
    wrapper.unmount()
  })

  it('折叠偏好持久化：localStorage=1 时初始即折叠', async () => {
    window.localStorage.setItem('cc.admin-sidebar.collapsed', '1')
    const { wrapper } = await mountLayout('TEACHER')

    expect(wrapper.find('[data-testid="admin-sidebar"]').classes()).toContain('w-16')
    wrapper.unmount()
  })

  it('localStorage 读写异常：折叠与分组展开降级为默认态，不抛错', async () => {
    const getSpy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation((key: string) => {
      if (key.startsWith('cc.admin-sidebar.')) {
        throw new Error('denied')
      }
      return null
    })
    const setSpy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation((key: string) => {
      if (key.startsWith('cc.admin-sidebar.')) {
        throw new Error('denied')
      }
    })
    const { wrapper } = await mountLayout('SUPER_ADMIN', '/knowledge/documents')

    // onMounted 读取异常 → 展开默认态正常渲染
    expect(wrapper.find('[data-testid="admin-sidebar"]').classes()).toContain('w-60')
    // toggle 写回异常不影响状态切换
    await wrapper.find('button[aria-label="收起侧栏"]').trigger('click')
    expect(wrapper.find('[data-testid="admin-sidebar"]').classes()).toContain('w-16')
    await wrapper.find('button[aria-label="展开侧栏"]').trigger('click')
    // 分组展开写回异常不影响交互
    const toggles = wrapper.findAll('[data-testid="nav-group-toggle"]')
    const auditToggle = toggles.find((t) => t.text().includes('审计'))
    await auditToggle?.trigger('click')
    expect(wrapper.text()).toContain('会话审计')
    getSpy.mockRestore()
    setSpy.mockRestore()
    wrapper.unmount()
  })

  it('分组展开偏好持久化：折叠过的分组重启后保持折叠', async () => {
    window.localStorage.setItem('cc.admin-sidebar.groups', JSON.stringify({ 知识库: false }))
    const { wrapper } = await mountLayout('SUPER_ADMIN', '/dashboard')

    // 知识库分组已折叠：子项不渲染
    expect(wrapper.text()).not.toContain('知识库管理')
    const toggles = wrapper.findAll('[data-testid="nav-group-toggle"]')
    const kbToggle = toggles.find((t) => t.text().includes('知识库'))
    expect(kbToggle?.attributes('aria-expanded')).toBe('false')
    wrapper.unmount()
  })

  it('面包屑：分组名 + 页面标题（文档详情 → 知识库 / 文档管理）', async () => {
    const { wrapper } = await mountLayout('TEACHER', '/knowledge/documents')

    const crumb = wrapper.find('[data-testid="breadcrumb"]')
    expect(crumb.text()).toContain('首页')
    expect(crumb.text()).toContain('知识库')
    expect(crumb.text()).toContain('文档管理')
    wrapper.unmount()
  })

  it('面包屑兜底：路由不在导航分组时仅展示页面标题', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    setLoginRole('TEACHER')
    // 定制路由器：布局壳挂一个不在导航分组中的子路由（无 meta.title 兜底空串）
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        {
          path: '/',
          component: AdminLayout,
          meta: { requiresAuth: true },
          children: [{ path: 'custom', component: { template: '<div>自定义页</div>' } }],
        },
      ],
    })
    await router.push('/custom')
    await router.isReady()
    const Shell = defineComponent(() => () => h(RouterView))
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const wrapper = mount(Shell, {
      global: { plugins: [pinia, router, [VueQueryPlugin, { queryClient }]] },
    })

    const crumb = wrapper.find('[data-testid="breadcrumb"]')
    expect(crumb.text()).toContain('首页')
    wrapper.unmount()
  })

  it('课程详情子路由切换：页面壳不重挂载（get 不重复拉取，评审 I1 回归锁）', async () => {
    courseApiMock.get.mockResolvedValue({
      id: '1',
      title: '分布式系统',
      description: '',
      coverImage: '',
      category: '计算机',
      instructorName: '张老师',
      price: 0,
      duration: '',
      tags: null,
      rating: 0,
      learningCount: 0,
      enrollmentLink: '',
      status: 'ACTIVE',
      createdBy: '1001',
      createdAt: '2026-08-25T00:00:00',
      contents: null,
      schedules: null,
      teacherIds: null,
    })
    courseApiMock.contents.mockResolvedValue([])
    const { wrapper, router } = await mountLayout('TEACHER', '/courses/1')

    // 概览子页就绪：课程元数据共 2 次（详情壳存在性校验 1 + 概览表单回填 1）
    await vi.waitFor(() => expect(wrapper.text()).toContain('基础信息'))
    expect(courseApiMock.get).toHaveBeenCalledTimes(2)

    // 切内容子页：键不变 → 壳存活，get 不再增加；内容由子视图经 contents 自行取数
    // （修复前 :key 挂 RouterView：任何导航整树重挂载，壳会再拉一次元数据 + 骨架闪烁）
    await router.push('/courses/1/content')
    await vi.waitFor(() => expect(wrapper.text()).toContain('课程内容'))
    expect(courseApiMock.get).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('头像下拉：显示名与角色展示，外点/Esc 关闭，退出登录清凭据并跳登录页', async () => {
    const { wrapper, router } = await mountLayout('SUPER_ADMIN')

    expect(wrapper.text()).not.toContain('退出登录')
    await wrapper.find('button[aria-label="用户菜单"]').trigger('click')
    expect(wrapper.text()).toContain('李超管')
    expect(wrapper.text()).toContain('超级管理员')
    expect(wrapper.text()).toContain('退出登录')

    // Esc 关闭
    await wrapper.find('button[aria-label="用户菜单"]').trigger('click')
    await wrapper.find('button[aria-label="用户菜单"]').trigger('click')
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await vi.waitFor(() => expect(wrapper.text()).not.toContain('退出登录'))

    // 外点关闭：展开后 pointerdown 落在布局根（菜单外）
    await wrapper.find('button[aria-label="用户菜单"]').trigger('click')
    document.body.dispatchEvent(new MouseEvent('pointerdown', { bubbles: true }))
    await vi.waitFor(() => expect(wrapper.text()).not.toContain('退出登录'))

    // 展开后执行退出：登出接口被调 + 清凭据 + 跳转登录页
    await wrapper.find('button[aria-label="用户菜单"]').trigger('click')
    const store = useAuthStore()
    const logoutSpy = vi.spyOn(store, 'logout').mockImplementation(async () => {
      store.clearAuth()
    })
    await wrapper.find('button[aria-label="退出登录"]').trigger('click')
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('login'))
    expect(logoutSpy).toHaveBeenCalledTimes(1)
    expect(store.isAuthenticated).toBe(false)
    wrapper.unmount()
  })
})
