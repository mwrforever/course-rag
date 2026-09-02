import { defineComponent, h, nextTick } from 'vue'
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
import { authApi } from '@/lib/api'
import { createAppRouter } from '@/router'
import { REFRESH_TOKEN_KEY, useAuthStore } from '@/stores/auth'

import type { UserRole } from '@/lib/types'

/**
 * 布局壳测试（Task 16 核心 + UI 重构 2026-08-27 白色侧栏 · Edukors 形态）
 *
 * 覆盖契约：
 * 1. createNavGroups 双层角色过滤：组级（审计仅超管）+ 项级（教师管理仅超管），空组剔除
 * 2. 白色侧栏：图标分组渲染（单子项直链/多子项手风琴默认态）、折叠切换与持久化、
 *    折叠态 data-tip tooltip、分组手风琴展开持久化、localStorage 异常降级
 * 3. 移动端抽屉：hamburger 开合 + 遮罩关闭 + 路由跳转自动收起（状态逻辑，
 *    视口断点由 CSS 承载，jsdom 不应用样式故按钮可直接交互）
 * 4. 顶栏面包屑（分组/页面标题）与用户下拉（N2 DropdownMenu：Esc/外点关闭 + 退出登录）
 * 5. 路由切换过渡容器与内容区统一限宽
 * 6. 启动恢复（M10）：refreshToken 在、displayName 空 → 首个导航前 fetchMe 恢复顶栏用户名与角色分组
 */

/** 课程接口调用记录（vi.fn 供「壳不重挂载」回归用例断言取数次数） */
const courseApiMock = vi.hoisted(() => ({
  get: vi.fn(),
  contents: vi.fn(),
}))

/** 仪表盘接口 mock：稳定数据（避免真实 axios 网络调用） */
vi.mock('@/lib/api', () => ({
  ApiError: class ApiError extends Error {},
  authApi: {
    // me 端点 mock（M10 启动恢复）：默认无实现，启动恢复用例内自行 mockResolvedValue
    me: vi.fn(),
  },
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
  // 意图统计（2026-08-27 仪表盘重构新增消费）：空数组 → donut/堆叠条区块空态
  feedbackApi: {
    stats: () => Promise.resolve([]),
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

describe('AdminLayout 渲染（白色侧栏 · Edukors 形态）', () => {
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

  /** 按子项文案定位其所属的手风琴子菜单容器（nav-group-children） */
  function findChildren(wrapper: ReturnType<typeof mount>, itemLabel: string) {
    return wrapper
      .findAll('[data-testid="nav-group-children"]')
      .find((c) => c.text().includes(itemLabel))
  }

  it('TEACHER：白色侧栏渲染基础分组，无超管导航；内容区统一限宽', async () => {
    const { wrapper } = await mountLayout('TEACHER')

    // 侧栏：白底（bg-surface 令牌）+ 展开态（无 is-collapsed）
    const aside = wrapper.find('[data-testid="admin-sidebar"]')
    expect(aside.classes()).toContain('bg-surface')
    expect(aside.classes()).not.toContain('is-collapsed')
    // 品牌名与基础导航
    expect(wrapper.text()).toContain('课程助手管理后台')
    expect(wrapper.text()).toContain('仪表盘')
    expect(wrapper.text()).toContain('知识库')
    expect(wrapper.text()).toContain('学生管理')
    expect(wrapper.text()).toContain('反馈')
    // 超管可见项不可见（角色过滤后整组不渲染）
    expect(wrapper.text()).not.toContain('教师管理')
    expect(wrapper.text()).not.toContain('审计')
    // 全局 1400px 内容容器（非视图层重复包裹）
    expect(wrapper.find('.max-w-\\[1400px\\]').exists()).toBe(true)
    await vi.waitFor(() => expect(wrapper.text()).toContain('文档总数'))
    wrapper.unmount()
  })

  it('多子项分组手风琴：默认展开组子菜单 open，审计组默认收起、点击展开', async () => {
    const { wrapper } = await mountLayout('SUPER_ADMIN', '/knowledge/documents')

    // 知识库分组默认展开：子菜单容器带 open（max-height 展开态）
    expect(findChildren(wrapper, '知识库管理')?.classes()).toContain('open')
    // 审计分组 defaultOpen=false：初始收起（aria-expanded + 子菜单无 open）
    const toggles = wrapper.findAll('[data-testid="nav-group-toggle"]')
    const auditToggle = toggles.find((t) => t.text().includes('审计'))
    expect(auditToggle?.attributes('aria-expanded')).toBe('false')
    expect(findChildren(wrapper, '会话审计')?.classes()).not.toContain('open')
    await auditToggle?.trigger('click')
    expect(auditToggle?.attributes('aria-expanded')).toBe('true')
    expect(findChildren(wrapper, '会话审计')?.classes()).toContain('open')
    wrapper.unmount()
  })

  it('折叠侧栏：切 80px 图标态（is-collapsed）并持久化 localStorage；展开恢复', async () => {
    const { wrapper } = await mountLayout('TEACHER')

    await wrapper.find('button[aria-label="收起侧栏"]').trigger('click')
    const aside = wrapper.find('[data-testid="admin-sidebar"]')
    expect(aside.classes()).toContain('is-collapsed')
    expect(window.localStorage.getItem('cc.admin-sidebar.collapsed')).toBe('1')
    // 折叠态：导航项携带 data-tip（CSS 悬浮气泡取值源）
    const tips = wrapper.findAll('[data-tip]').map((t) => t.attributes('data-tip'))
    expect(tips).toContain('仪表盘')
    expect(tips).toContain('知识库')
    // 折叠态折叠钮 aria-label 翻转为「展开侧栏」
    await wrapper.find('button[aria-label="展开侧栏"]').trigger('click')
    expect(aside.classes()).not.toContain('is-collapsed')
    expect(window.localStorage.getItem('cc.admin-sidebar.collapsed')).toBe('0')
    wrapper.unmount()
  })

  it('折叠偏好持久化：localStorage=1 时初始即折叠', async () => {
    window.localStorage.setItem('cc.admin-sidebar.collapsed', '1')
    const { wrapper } = await mountLayout('TEACHER')

    expect(wrapper.find('[data-testid="admin-sidebar"]').classes()).toContain('is-collapsed')
    wrapper.unmount()
  })

  it('折叠态点分组标题：先展开侧栏，不切换手风琴（设计稿行为）', async () => {
    const { wrapper } = await mountLayout('SUPER_ADMIN', '/knowledge/documents')

    await wrapper.find('button[aria-label="收起侧栏"]').trigger('click')
    // 折叠态点审计标题（默认收起）：侧栏恢复展开宽度，审计分组未被切换仍收起，
    // 知识库分组（默认展开）随侧栏恢复重新展开
    const auditToggle = wrapper
      .findAll('[data-testid="nav-group-toggle"]')
      .find((t) => t.text().includes('审计'))
    await auditToggle?.trigger('click')
    const aside = wrapper.find('[data-testid="admin-sidebar"]')
    expect(aside.classes()).not.toContain('is-collapsed')
    expect(findChildren(wrapper, '会话审计')?.classes()).not.toContain('open')
    expect(findChildren(wrapper, '知识库管理')?.classes()).toContain('open')
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
    const aside = wrapper.find('[data-testid="admin-sidebar"]')
    expect(aside.classes()).not.toContain('is-collapsed')
    // toggle 写回异常不影响状态切换
    await wrapper.find('button[aria-label="收起侧栏"]').trigger('click')
    expect(aside.classes()).toContain('is-collapsed')
    await wrapper.find('button[aria-label="展开侧栏"]').trigger('click')
    expect(aside.classes()).not.toContain('is-collapsed')
    // 分组展开写回异常不影响交互
    const auditToggle = wrapper
      .findAll('[data-testid="nav-group-toggle"]')
      .find((t) => t.text().includes('审计'))
    await auditToggle?.trigger('click')
    expect(findChildren(wrapper, '会话审计')?.classes()).toContain('open')
    getSpy.mockRestore()
    setSpy.mockRestore()
    wrapper.unmount()
  })

  it('分组展开偏好持久化：收起过的分组重启后保持收起', async () => {
    window.localStorage.setItem('cc.admin-sidebar.groups', JSON.stringify({ 知识库: false }))
    const { wrapper } = await mountLayout('SUPER_ADMIN', '/dashboard')

    // 知识库分组已收起：子菜单无 open + 标题 aria-expanded=false
    expect(findChildren(wrapper, '知识库管理')?.classes()).not.toContain('open')
    const kbToggle = wrapper
      .findAll('[data-testid="nav-group-toggle"]')
      .find((t) => t.text().includes('知识库'))
    expect(kbToggle?.attributes('aria-expanded')).toBe('false')
    wrapper.unmount()
  })

  it('移动端抽屉：hamburger 打开滑入（mobile-open + 遮罩），遮罩与路由跳转关闭', async () => {
    const { wrapper, router } = await mountLayout('TEACHER')

    const aside = wrapper.find('[data-testid="admin-sidebar"]')
    // 初始：抽屉关闭、无遮罩（断点显隐由 CSS 承载，此处验证状态逻辑）
    expect(aside.classes()).not.toContain('mobile-open')
    expect(wrapper.find('[data-testid="drawer-backdrop"]').exists()).toBe(false)
    // hamburger 唤起：侧栏滑入标记 + 遮罩显现
    await wrapper.find('button[aria-label="打开菜单"]').trigger('click')
    expect(aside.classes()).toContain('mobile-open')
    expect(wrapper.find('[data-testid="drawer-backdrop"]').exists()).toBe(true)
    // 遮罩点击关闭
    await wrapper.find('[data-testid="drawer-backdrop"]').trigger('click')
    expect(aside.classes()).not.toContain('mobile-open')
    expect(wrapper.find('[data-testid="drawer-backdrop"]').exists()).toBe(false)
    // 重新打开后经路由跳转自动收起（抽屉内导航不残留遮罩）
    await wrapper.find('button[aria-label="打开菜单"]').trigger('click')
    await router.push('/students')
    await nextTick()
    expect(aside.classes()).not.toContain('mobile-open')
    wrapper.unmount()
  })

  it('窗口尺寸变化：指示条按新几何复测落位（resize 监听）', async () => {
    const { wrapper } = await mountLayout('TEACHER')

    // 挂载即量测：/dashboard 激活项在场，指示条可见（jsdom 无布局，offsetTop/offsetHeight 恒 0）
    const indicatorEl = wrapper.find('.nav-indicator')
    expect(indicatorEl.attributes('style')).toContain('opacity: 1')
    expect(indicatorEl.attributes('style')).toContain('top: 0px')

    // 模拟布局变化（如跨过移动端断点）：改写激活项量测值后触发 resize，
    // 指示条应按新 offsetTop 重新落位——覆盖 onResize 监听（N9 补测，铁律 100% 行覆盖）
    const active = wrapper.find('[data-nav-active="true"]').element as HTMLElement
    Object.defineProperty(active, 'offsetTop', { value: 120, configurable: true })
    window.dispatchEvent(new Event('resize'))
    await nextTick()
    expect(indicatorEl.attributes('style')).toContain('top: 120px')
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

  it('面包屑去重：分组名与页面标题同名（仪表盘）不重复渲染层级', async () => {
    const { wrapper } = await mountLayout('TEACHER', '/dashboard')

    // 修复前：仪表盘为单子项直链分组且分组名与 meta.title 同名，
    // 面包屑渲染「首页 / 仪表盘 / 仪表盘」末两级重复（N9 视觉核对未决差异 #1）
    const text = wrapper.find('[data-testid="breadcrumb"]').text()
    expect(text).toContain('首页')
    expect(text.match(/仪表盘/g)).toHaveLength(1)
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

    // 初始关闭（DropdownMenu v-if 卸载菜单弹层）
    expect(wrapper.find('[data-testid="user-menu"]').exists()).toBe(false)
    await wrapper.find('button[aria-label="用户菜单"]').trigger('click')
    const menu = wrapper.find('[data-testid="user-menu"]')
    expect(menu.text()).toContain('李超管')
    expect(menu.text()).toContain('超级管理员')
    expect(menu.text()).toContain('退出登录')

    // Esc 关闭（N2 DropdownMenu 的 window 级监听）
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await nextTick()
    expect(wrapper.find('[data-testid="user-menu"]').exists()).toBe(false)

    // 外点关闭：window pointerdown 落点在组件外
    await wrapper.find('button[aria-label="用户菜单"]').trigger('click')
    window.dispatchEvent(new MouseEvent('pointerdown'))
    await nextTick()
    expect(wrapper.find('[data-testid="user-menu"]').exists()).toBe(false)

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

  it('启动恢复（M10）：refreshToken 在、displayName 空 → 首个导航前 fetchMe 恢复顶栏用户名与角色导航分组', async () => {
    // 刷新后场景：RT 在 sessionStorage、AT 与身份字段内存态全空——修复前顶栏恒「未登录」、
    // role=null 按最低权限渲染（超管分组不可见）；启动恢复后两处均正常
    sessionStorage.setItem(REFRESH_TOKEN_KEY, 'rt-1')
    vi.mocked(authApi.me).mockResolvedValue({
      userId: '1001',
      role: 'SUPER_ADMIN',
      displayName: '李超管',
    })
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = createAppRouter()

    // 首个导航触发启动恢复：守卫 await fetchMe（RT 在且 displayName 空时发请求）
    await router.push('/dashboard')
    await router.isReady()
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const Shell = defineComponent(() => () => h(RouterView))
    const wrapper = mount(Shell, {
      global: { plugins: [pinia, router, [VueQueryPlugin, { queryClient }]] },
    })

    // 顶栏用户名恢复（不再显示「未登录」）
    await vi.waitFor(() => expect(wrapper.text()).toContain('李超管'))
    expect(wrapper.text()).not.toContain('未登录')
    // 角色恢复后超管导航分组正常（role=null 按最低权限渲染的问题一并覆盖）
    await vi.waitFor(() => expect(wrapper.text()).toContain('审计'))
    expect(wrapper.text()).toContain('教师管理')
    wrapper.unmount()
  })
})
