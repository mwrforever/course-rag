<script lang="ts">
/**
 * B 端布局壳：顶栏 56px slate-900 + 侧导航 220px 分组 + 内容区（设计 §2.3）
 *
 * 导航分组静态结构定义与角色过滤纯函数放普通 script 块导出，供单元测试直接断言
 * 角色过滤逻辑（Task 16 核心交付：TEACHER 隐藏会话审计/安全审计，SUPER_ADMIN 全量可见）。
 */
import type { UserRole } from '@/lib/types'

/** 导航项：to 与路由表路径一一对应（title 语义见 router meta） */
export interface NavItem {
  label: string
  to: string
}

/** 侧导航分组：roles 缺省表示两角色可见；仅超管分组显式标注（设计 §2.3 尾部 ⚠ 行） */
export interface NavGroup {
  label: string
  items: NavItem[]
  roles?: UserRole[]
}

/** 侧导航静态结构（设计 §2.3：仪表盘 / 知识库[文档、分片] / 课程 / 用户 / 反馈 / 会话审计 / 安全审计） */
export const navGroups: NavGroup[] = [
  { label: '仪表盘', items: [{ label: '仪表盘', to: '/dashboard' }] },
  {
    label: '知识库',
    items: [
      { label: '文档', to: '/knowledge/documents' },
      { label: '分片', to: '/knowledge/chunks' },
    ],
  },
  { label: '课程', items: [{ label: '课程', to: '/courses' }] },
  { label: '用户', items: [{ label: '用户', to: '/users' }] },
  { label: '反馈', items: [{ label: '反馈', to: '/feedback' }] },
  { label: '会话审计', items: [{ label: '会话审计', to: '/sessions' }], roles: ['SUPER_ADMIN'] },
  {
    label: '安全审计',
    items: [{ label: '安全审计', to: '/security/login-records' }],
    roles: ['SUPER_ADMIN'],
  },
]

/**
 * 按角色过滤侧导航分组（核心角色过滤逻辑）
 *
 * @param role 当前登录角色；登录态未恢复（刷新后 role=null）按最低权限渲染（超管分组不可见，
 *             待 api 层静默刷新恢复后由路由重新渲染）
 * @returns 过滤后的可见分组
 */
export function createNavGroups(role: UserRole | null): NavGroup[] {
  return navGroups.filter((group) => (group.roles ? group.roles.includes(role as UserRole) : true))
}
</script>

<script setup lang="ts">
/**
 * 布局壳脚本：顶栏用户下拉（显示名/角色 + 退出登录）、侧导航激活态、页头标题
 *
 * 依赖：useAuthStore（凭据与角色）、vue-router（导航与标题）。
 */
import { computed, ref } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { PhCaretDown, PhDatabase, PhSignOut, PhUserCircle } from '@phosphor-icons/vue'

import { useAuthStore } from '@/stores/auth'
import { cn } from '@/lib/utils'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

/** 经角色过滤后的可见导航分组 */
const groups = computed(() => createNavGroups(auth.role))

/** 头像下拉菜单展开态（点击切换，Esc 关闭由浏览器焦点流兜底） */
const menuOpen = ref(false)

/**
 * 导航项激活判定：精确匹配或子路径前缀匹配（如文档详情 /knowledge/documents/:id 高亮「文档」）
 *
 * @param to 导航项目标路径
 * @returns 当前路由是否命中
 */
function isActive(to: string): boolean {
  return route.path === to || (to !== '/dashboard' && route.path.startsWith(`${to}/`))
}

/**
 * 导航链接样式组：激活态 brand-soft 底 + brand-strong 文字，静置态 muted + hover surface-2
 *
 * @param to 导航项目标路径（激活判定入参）
 * @returns 合并后的 Tailwind 类名（tokens 语义层，禁止裸色值）
 */
function navLinkClass(to: string): string {
  return cn(
    'flex items-center gap-2 rounded-lg px-4 py-2.5 text-sm transition-colors duration-150',
    isActive(to)
      ? 'bg-brand-soft font-medium text-brand-strong'
      : 'text-text-muted hover:bg-surface-2 hover:text-text',
  )
}

/** 退出登录：登出接口（幂等容错）→ 清理凭据 → 回登录页 */
async function handleLogout() {
  await auth.logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <div class="flex min-h-screen flex-col bg-bg text-text">
    <!-- 顶栏 56px：bg-slate-900，Logo + 品牌名 · 右侧头像下拉（设计 §2.3） -->
    <header class="flex h-14 shrink-0 items-center justify-between bg-slate-900 px-6 text-white">
      <div class="flex items-center gap-2.5">
        <div
          class="flex h-7 w-7 items-center justify-center rounded-lg bg-gradient-to-br from-blue-600 to-blue-300"
        >
          <PhDatabase class="h-4 w-4 text-white" weight="bold" />
        </div>
        <span class="text-sm font-semibold tracking-wide">知识库管理后台</span>
      </div>
      <div class="relative">
        <button
          type="button"
          aria-label="用户菜单"
          class="flex items-center gap-2 rounded-lg px-2 py-1.5 text-sm text-slate-200 transition-colors duration-150 hover:bg-slate-700/60"
          @click="menuOpen = !menuOpen"
        >
          <PhUserCircle class="h-5 w-5" weight="duotone" />
          <span>{{ auth.displayName ?? '未登录' }}</span>
          <PhCaretDown class="h-3.5 w-3.5" />
        </button>
        <!-- 头像下拉菜单：显示名 + 角色 + 退出登录 -->
        <div
          v-if="menuOpen"
          class="absolute right-0 top-11 z-50 w-48 rounded-xl border border-border bg-surface p-1.5 shadow-md"
        >
          <div class="border-b border-border px-3 py-2">
            <p class="text-sm font-medium text-text">{{ auth.displayName ?? '' }}</p>
            <p class="mt-0.5 text-xs text-text-muted">{{ auth.role ?? '' }}</p>
          </div>
          <button
            type="button"
            aria-label="退出登录"
            class="mt-1 flex w-full items-center gap-2 rounded-lg px-3 py-2 text-sm text-danger transition-colors duration-150 hover:bg-danger/5"
            @click="handleLogout"
          >
            <PhSignOut class="h-4 w-4" />
            退出登录
          </button>
        </div>
      </div>
    </header>

    <div class="flex min-h-0 flex-1">
      <!-- 侧导航 220px：surface 底 + 右侧 1px 分隔，按分组渲染（超管分组已按角色过滤） -->
      <aside class="w-[220px] shrink-0 border-r border-border bg-surface">
        <nav class="sticky top-0 p-3">
          <template v-for="group in groups" :key="group.label">
            <!-- 单子项分组：直接渲染链接（仪表盘/课程/用户/反馈/会话审计/安全审计） -->
            <template v-if="group.items.length === 1">
              <RouterLink :to="group.items[0].to" :class="navLinkClass(group.items[0].to)">
                {{ group.items[0].label }}
              </RouterLink>
            </template>
            <!-- 多子项分组：组标题 + 子项链接（知识库[文档、分片]） -->
            <template v-else>
              <p
                class="px-4 pb-1 pt-3 text-xs font-semibold uppercase tracking-wider text-text-subtle"
              >
                {{ group.label }}
              </p>
              <RouterLink
                v-for="item in group.items"
                :key="item.to"
                :to="item.to"
                :class="navLinkClass(item.to)"
              >
                {{ item.label }}
              </RouterLink>
            </template>
          </template>
        </nav>
      </aside>

      <!-- 内容区：限宽居中 + 页头（H1 标题 + 页面操作插槽，设计 §2.3） -->
      <main class="min-w-0 flex-1">
        <div class="mx-auto max-w-[1400px] px-8 py-6">
          <header class="mb-6 flex items-start justify-between gap-4">
            <h1 class="text-2xl font-bold text-text">{{ route.meta.title ?? '' }}</h1>
            <div class="flex items-center gap-3">
              <slot name="page-header" />
            </div>
          </header>
          <!-- 子路由页面渲染出口（业务页面在内容区渲染，勿改为 slot） -->
          <RouterView />
        </div>
      </main>
    </div>
  </div>
</template>
