<script lang="ts">
/**
 * B 端布局壳（UI 重构 2026-08-25：深色侧栏 + 浅色内容区现代管理风格）
 *
 * 侧导航：深色石墨蓝（ink 层）+ 图标 + 可展开分组（知识库/学员/审计，chevron 旋转），
 * 激活态左侧品牌光条 + 半透明白底；整栏可折叠（64px 图标态，localStorage 持久化）。
 * 顶栏：面包屑（分组/页面）+ 用户下拉（显示名/角色 + 退出登录，Esc/外点关闭）。
 * 内容区：统一容器（视图不再自带 main 包裹，修复双重 padding 缺陷）；页面 vnode 按
 * resolvePageKey 身份键挂 key（子路由切换壳存活、跨实体重挂载重取数）。
 *
 * 导航分组静态结构定义与角色过滤纯函数放普通 script 块导出，供单元测试直接断言
 * 角色过滤逻辑（TEACHER 隐藏学员-教师管理/审计分组，SUPER_ADMIN 全量可见）。
 */
import type { Component } from 'vue'
import {
  PhChatCircleDots,
  PhDatabase,
  PhGraduationCap,
  PhShieldCheck,
  PhSquaresFour,
  PhStudent,
  PhUsers,
} from '@phosphor-icons/vue'
import type { UserRole } from '@/lib/types'

/** 导航项：图标为 phosphor 组件引用（静态表可序列化断言 label/to） */
export interface NavItem {
  label: string
  to: string
  icon: Component
  /** 单项角色白名单（缺省随分组两角色可见；教师管理仅超管） */
  roles?: UserRole[]
}

/** 侧导航分组：children 多子项可展开；roles 缺省表示两角色可见；仅超管分组显式标注 */
export interface NavGroup {
  label: string
  icon: Component
  items: NavItem[]
  roles?: UserRole[]
  /** 分组默认展开（多子项分组可折叠；false 时默认收起） */
  defaultOpen?: boolean
}

/** 侧导航静态结构（UI 重构 2026-08-25：仪表盘 / 知识库[管理、文档、分片] / 课程 /
 *  学员[学生、教师(超管)] / 反馈 / 审计[会话、登录记录、Token 黑名单(超管)]） */
export const navGroups: NavGroup[] = [
  {
    label: '仪表盘',
    icon: PhSquaresFour,
    items: [{ label: '仪表盘', to: '/dashboard', icon: PhSquaresFour }],
  },
  {
    label: '知识库',
    icon: PhDatabase,
    items: [
      { label: '知识库管理', to: '/knowledge-bases', icon: PhDatabase },
      { label: '文档', to: '/knowledge/documents', icon: PhDatabase },
      { label: '分片', to: '/knowledge/chunks', icon: PhDatabase },
    ],
  },
  {
    label: '课程',
    icon: PhGraduationCap,
    items: [{ label: '课程管理', to: '/courses', icon: PhGraduationCap }],
  },
  {
    label: '学员',
    icon: PhUsers,
    items: [
      { label: '学生管理', to: '/students', icon: PhStudent },
      { label: '教师管理', to: '/teachers', icon: PhUsers, roles: ['SUPER_ADMIN'] },
    ],
  },
  {
    label: '反馈',
    icon: PhChatCircleDots,
    items: [{ label: '反馈报表', to: '/feedback', icon: PhChatCircleDots }],
  },
  {
    label: '审计',
    icon: PhShieldCheck,
    items: [
      { label: '会话审计', to: '/sessions', icon: PhShieldCheck },
      { label: '登录记录', to: '/security/login-records', icon: PhShieldCheck },
      { label: 'Token 黑名单', to: '/security/token-blacklist', icon: PhShieldCheck },
    ],
    defaultOpen: false,
    roles: ['SUPER_ADMIN'],
  },
]

/**
 * 按角色过滤侧导航分组（组级 + 单项级双层白名单）
 *
 * @param role 当前登录角色；登录态未恢复（刷新后 role=null）按最低权限渲染（超管分组不可见，
 *             待 api 层静默刷新恢复后由路由重新渲染）
 * @returns 过滤后的可见分组（组内单项按 roles 过滤，空组剔除）
 */
export function createNavGroups(role: UserRole | null): NavGroup[] {
  return navGroups
    .filter((group) => (group.roles ? group.roles.includes(role as UserRole) : true))
    .map((group) => ({
      ...group,
      items: group.items.filter((item) =>
        item.roles ? item.roles.includes(role as UserRole) : true,
      ),
    }))
    .filter((group) => group.items.length > 0)
}

/** 导航项激活判定：精确匹配或子路径前缀匹配（文档详情 /knowledge/documents/:id 高亮「文档」） */
export function isGroupActive(pathname: string, to: string): boolean {
  return pathname === to || (to !== '/dashboard' && pathname.startsWith(`${to}/`))
}

/** 页面身份键输入的最小路由投影（解耦 vue-router 类型，纯函数可直接单测） */
export interface RouteKeySource {
  matched: { path: string }[]
  params: Record<string, string | string[]>
  path: string
}

/**
 * 页面级过渡身份键（评审修复 I1 2026-08-25：原 :key 挂 RouterView 致导航整树重挂载）
 *
 * 键 = 布局壳内第一层页面路由记录（matched[1]）的 path 定义 + 路径参数序列化：
 * - 同一实体页内子路由切换（如课程详情概览↔内容↔排期）键不变 → 页面壳与 Transition
 *   存活，仅壳内子出口换页（壳不重拉课程元数据，无骨架闪烁）
 * - 跨实体导航（/courses/1 → /courses/2）或跨页导航参数/定义变化 → 键变化 → 重挂载
 *   重新取数，详情壳无需自行 watch 参数（DocumentDetailView 等参数页同规则覆盖）
 *
 * @param route 当前路由（matched/params/path 最小投影）
 * @returns 过渡 key 字符串
 */
export function resolvePageKey(route: RouteKeySource): string {
  return `${route.matched[1]?.path ?? route.path}|${JSON.stringify(route.params)}`
}
</script>

<script setup lang="ts">
/**
 * 布局壳脚本：侧栏折叠/分组展开、顶栏面包屑、用户下拉（点击外部/Esc 关闭）、路由过渡
 *
 * 依赖：useAuthStore（凭据与角色）、vue-router（导航与标题）。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { PhCaretDown, PhCaretLeft, PhCaretRight, PhHouse, PhSignOut } from '@phosphor-icons/vue'

import { useAuthStore } from '@/stores/auth'
import { cn } from '@/lib/utils'

/** 侧栏折叠偏好 localStorage 键 */
const SIDEBAR_STORAGE_KEY = 'cc.admin-sidebar.collapsed'
/** 分组展开态默认值（默认展开的分组收进 collapseMap 管理） */
const GROUPS_STORAGE_KEY = 'cc.admin-sidebar.groups'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

/** 经角色过滤后的可见导航分组 */
const groups = computed(() => createNavGroups(auth.role))

// ── 侧栏折叠态（64px 图标态，偏好持久化）──
const collapsed = ref(false)
onMounted(() => {
  try {
    collapsed.value = window.localStorage.getItem(SIDEBAR_STORAGE_KEY) === '1'
  } catch {
    /* 隐私模式等场景按展开处理 */
  }
})
function toggleCollapsed() {
  collapsed.value = !collapsed.value
  try {
    window.localStorage.setItem(SIDEBAR_STORAGE_KEY, collapsed.value ? '1' : '0')
  } catch {
    /* 同上 */
  }
}

// ── 分组展开态：显式状态表（label → 是否展开），缺省按 defaultOpen；用户操作后持久化 ──
// 2026-08-25 修正：旧实现以「折叠集合」表示，默认折叠分组（审计）点击后语义相反永远打不开，
// 改为显式布尔表，默认展开/默认折叠分组行为一致。
const groupState = ref<Record<string, boolean>>({})
onMounted(() => {
  try {
    const raw = window.localStorage.getItem(GROUPS_STORAGE_KEY)
    if (raw) {
      groupState.value = JSON.parse(raw) as Record<string, boolean>
    }
  } catch {
    // 隐私模式等场景按默认展开态处理
  }
})
function isGroupOpen(group: NavGroup): boolean {
  return groupState.value[group.label] ?? group.defaultOpen ?? true
}
function toggleGroup(group: NavGroup) {
  const next = { ...groupState.value, [group.label]: !isGroupOpen(group) }
  groupState.value = next
  try {
    window.localStorage.setItem(GROUPS_STORAGE_KEY, JSON.stringify(next))
  } catch {
    // 同上
  }
}

// ── 路由切换过渡键：页面级身份（见 resolvePageKey 注释，评审修复 I1）──
const pageKey = computed(() => resolvePageKey(route))

// ── 顶栏面包屑：由导航分组定位「分组名 / 页面标题」──
const breadcrumbs = computed(() => {
  const group = groups.value.find((g) => g.items.some((item) => isGroupActive(route.path, item.to)))
  if (!group) {
    return [{ label: route.meta.title ?? '' }]
  }
  return [{ label: group.label }, { label: route.meta.title ?? '' }]
})

// ── 用户下拉（点击外部/Esc 关闭，修复历史缺陷）──
const menuOpen = ref(false)
/** 用户菜单容器引用：外点关闭判定用（ref 优于 getElementById，测试 detached 容器同样可用） */
const menuRef = ref<HTMLElement | null>(null)
function onDocumentPointer(event: PointerEvent) {
  if (menuRef.value && !menuRef.value.contains(event.target as Node)) {
    menuOpen.value = false
  }
}
function onDocumentKey(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    menuOpen.value = false
  }
}
onMounted(() => {
  document.addEventListener('pointerdown', onDocumentPointer)
  document.addEventListener('keydown', onDocumentKey)
})
onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDocumentPointer)
  document.removeEventListener('keydown', onDocumentKey)
})

/** 退出登录：登出接口（幂等容错）→ 清理凭据 → 回登录页 */
async function handleLogout() {
  await auth.logout()
  router.push({ name: 'login' })
}

/** 导航链接样式：激活态左侧光条 + 半透明白底；静置态 ink 浅文字 hover 加深 */
function navLinkClass(active: boolean): string {
  return cn(
    'group/nav relative flex h-9 w-full items-center gap-2.5 rounded-lg text-sm transition-colors duration-150',
    collapsed.value ? 'justify-center px-0' : 'px-3',
    active
      ? 'bg-white/10 font-medium text-white'
      : 'text-slate-400 hover:bg-white/5 hover:text-white',
  )
}
</script>

<template>
  <div class="flex min-h-screen bg-bg text-text">
    <!-- ===== 深色侧栏（ink 石墨蓝渐变，固定满高） ===== -->
    <aside
      data-testid="admin-sidebar"
      class="flex shrink-0 flex-col bg-gradient-to-b from-ink-950 to-ink-900 transition-[width] duration-200 ease-out"
      :class="collapsed ? 'w-16' : 'w-60'"
    >
      <!-- 品牌区 -->
      <div class="flex h-14 shrink-0 items-center gap-2.5 px-4">
        <template v-if="collapsed">
          <button
            type="button"
            aria-label="展开侧栏"
            class="mx-auto grid size-8 place-items-center rounded-lg text-slate-400 transition-colors hover:bg-white/10 hover:text-white"
            @click="toggleCollapsed"
          >
            <PhCaretRight class="h-4 w-4" />
          </button>
        </template>
        <template v-else>
          <div
            class="flex size-8 shrink-0 items-center justify-center rounded-lg bg-gradient-to-br from-blue-400 to-blue-600 shadow-md shadow-blue-900/40"
          >
            <PhSquaresFour class="size-4 text-white" weight="fill" />
          </div>
          <span class="flex-1 text-sm font-semibold tracking-wide text-white"
            >课程助手管理后台</span
          >
          <button
            type="button"
            aria-label="收起侧栏"
            class="grid size-8 place-items-center rounded-lg text-slate-400 transition-colors hover:bg-white/10 hover:text-white"
            @click="toggleCollapsed"
          >
            <PhCaretLeft class="h-4 w-4" />
          </button>
        </template>
      </div>

      <!-- 导航分组 -->
      <nav class="min-h-0 flex-1 overflow-y-auto px-2.5 py-3">
        <template v-for="group in groups" :key="group.label">
          <!-- 单子项分组：直接渲染链接（仪表盘/课程/反馈） -->
          <template v-if="group.items.length === 1">
            <RouterLink
              :to="group.items[0].to"
              :class="navLinkClass(isGroupActive(route.path, group.items[0].to))"
            >
              <component :is="group.items[0].icon" class="size-4 shrink-0" />
              <span v-if="!collapsed" class="truncate">{{ group.items[0].label }}</span>
            </RouterLink>
          </template>

          <!-- 多子项分组：可展开组标题 + 子项（知识库/学员/审计） -->
          <template v-else>
            <button
              type="button"
              :aria-expanded="isGroupOpen(group)"
              data-testid="nav-group-toggle"
              class="flex h-9 w-full items-center gap-2.5 rounded-lg text-sm text-slate-400 transition-colors duration-150 hover:bg-white/5 hover:text-white"
              :class="collapsed ? 'justify-center px-0' : 'px-3'"
              @click="toggleGroup(group)"
            >
              <component :is="group.icon" class="size-4 shrink-0" />
              <span v-if="!collapsed" class="flex-1 truncate text-left">{{ group.label }}</span>
              <PhCaretDown
                v-if="!collapsed"
                class="size-3.5 shrink-0 transition-transform duration-200"
                :class="isGroupOpen(group) ? '' : '-rotate-90'"
              />
            </button>
            <!-- 子项列表（展开时渲染，行内滑入淡出） -->
            <div
              v-if="isGroupOpen(group) && !collapsed"
              data-testid="nav-group-children"
              class="mt-0.5 mb-1.5 space-y-0.5 pl-2"
            >
              <RouterLink
                v-for="item in group.items"
                :key="item.to"
                :to="item.to"
                :class="navLinkClass(isGroupActive(route.path, item.to))"
              >
                <span
                  aria-hidden
                  class="absolute left-2 top-1/2 h-3.5 w-0.5 -translate-y-1/2 rounded-full bg-blue-400 transition-opacity"
                  :class="isGroupActive(route.path, item.to) ? 'opacity-100' : 'opacity-0'"
                />
                <span class="truncate pl-2">{{ item.label }}</span>
              </RouterLink>
            </div>
          </template>
        </template>
      </nav>

      <!-- 侧栏底部：折叠开关（展开态下品牌区已有）与版本信息 -->
      <div class="shrink-0 border-t border-white/10 p-3">
        <p v-if="!collapsed" class="px-2 text-[11px] text-slate-500">v1.0 · RAG 课程助手</p>
      </div>
    </aside>

    <!-- ===== 浅色内容区 ===== -->
    <div class="flex min-w-0 flex-1 flex-col">
      <!-- 顶栏：面包屑 + 用户下拉 -->
      <header
        class="flex h-14 shrink-0 items-center justify-between border-b border-border bg-surface px-6"
      >
        <nav aria-label="面包屑" class="flex items-center gap-1.5 text-sm" data-testid="breadcrumb">
          <RouterLink
            to="/dashboard"
            class="flex items-center gap-1.5 text-text-subtle transition-colors hover:text-brand-strong"
          >
            <PhHouse class="size-3.5" />
            首页
          </RouterLink>
          <template v-for="(crumb, index) in breadcrumbs" :key="crumb.label">
            <span class="text-text-subtle">/</span>
            <span
              :class="
                index === breadcrumbs.length - 1 ? 'font-medium text-text' : 'text-text-muted'
              "
            >
              {{ crumb.label }}
            </span>
          </template>
        </nav>

        <div ref="menuRef" class="relative">
          <button
            type="button"
            aria-label="用户菜单"
            :aria-expanded="menuOpen"
            class="flex items-center gap-2 rounded-lg px-2 py-1.5 text-sm text-text-muted transition-colors duration-150 hover:bg-surface-2 hover:text-text"
            @click="menuOpen = !menuOpen"
          >
            <span
              class="bg-gradient-to-br from-blue-400 to-blue-600 grid size-7 place-items-center rounded-full text-xs font-bold text-white"
            >
              {{ (auth.displayName ?? '管').charAt(0) }}
            </span>
            <span class="hidden sm:inline">{{ auth.displayName ?? '未登录' }}</span>
            <PhCaretDown class="size-3.5" />
          </button>
          <!-- 头像下拉菜单：显示名 + 角色 + 退出登录 -->
          <div
            v-if="menuOpen"
            data-testid="user-menu"
            class="animate-menu-in absolute right-0 top-11 z-50 w-52 overflow-hidden rounded-xl border border-border bg-surface p-1.5 shadow-lg"
          >
            <div class="border-b border-border px-3 py-2">
              <p class="text-sm font-medium text-text">{{ auth.displayName ?? '' }}</p>
              <p class="mt-0.5 text-xs text-text-muted">
                {{
                  auth.role === 'SUPER_ADMIN'
                    ? '超级管理员'
                    : auth.role === 'TEACHER'
                      ? '教师'
                      : (auth.role ?? '')
                }}
              </p>
            </div>
            <button
              type="button"
              aria-label="退出登录"
              class="mt-1 flex w-full items-center gap-2 rounded-lg px-3 py-2 text-sm text-danger transition-colors duration-150 hover:bg-danger/5"
              @click="handleLogout"
            >
              <PhSignOut class="size-4" />
              退出登录
            </button>
          </div>
        </div>
      </header>

      <!-- 内容区：统一容器（视图不再自带 main 包裹）。
           key 必须挂在页面 vnode 上（评审修复 I1：原挂 RouterView 致每次导航整树重挂载、
           课程详情壳重复取数）。页面淡入过渡暂缺：<Transition> 包裹本插槽在当前
           vue@3.5.41 + vue-router 组合下导航后新视图永不挂载（真实浏览器实证，无论
           key 取 route.path 还是计算键、是否 mode=out-in 均复现），旧实现把 key 挂
           RouterView 属绕开该缺陷的变通、过渡从未真正播放；待依赖升级后重评（TASK.md §6） -->
      <main class="min-w-0 flex-1">
        <div class="mx-auto w-full max-w-[1400px] px-6 py-6">
          <RouterView v-slot="{ Component: PageComponent }">
            <component :is="PageComponent" :key="pageKey" />
          </RouterView>
        </div>
      </main>
    </div>
  </div>
</template>
