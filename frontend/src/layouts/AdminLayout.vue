<script lang="ts">
/**
 * B 端布局壳（UI 重构 2026-08-27 紫系换肤 N3：视觉基准 Edukors 设计稿侧栏/顶栏）
 *
 * 侧栏：白色 256px（--sw）/ 折叠 80px（--swc），右缘外凸圆形折叠钮（hover 缩放、
 * 折叠态弹簧旋转 180°）、激活指示条（绝对定位 + 弹簧曲线位移）、多子项分组手风琴
 * （max-height 过渡 + chevron 旋转 + 展开持久化）、折叠态 data-tip 深底 tooltip；
 * 折叠/分组展开偏好 localStorage 持久化；≤900px 抽屉形态（hamburger + backdrop 遮罩
 * + transform 滑入）。顶栏：sticky 毛玻璃（bg 80% + blur）+ 面包屑 + 用户下拉迁移
 * N2 DropdownMenu 组件（Esc/外点关闭内建，退出登录流保留）。
 * 内容区：统一容器（视图不再自带 main 包裹）；页面 vnode 按 resolvePageKey 身份键挂
 * key（子路由切换壳存活、跨实体重挂载重取数）。
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
 * 布局壳脚本：侧栏折叠/分组手风琴/激活指示条量测、移动端抽屉、顶栏面包屑、
 * 用户下拉（N2 DropdownMenu 组件承载 Esc/外点关闭）、路由过渡键
 *
 * 依赖：useAuthStore（凭据与角色）、vue-router（导航与标题）、
 * DropdownMenu/DropdownMenuItem（N2 设计系统下拉组件）、vReveal（滚动入场指令，
 * 局部注册以兼容不经 main.ts 装配的单元测试挂载）。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { PhCaretDown, PhCaretLeft, PhHouse, PhList, PhSignOut } from '@phosphor-icons/vue'

import { useAuthStore } from '@/stores/auth'
import { DropdownMenu, DropdownMenuItem } from '@/components/ui/dropdown-menu'
import { vReveal } from '@/directives/reveal'

/** 侧栏折叠偏好 localStorage 键 */
const SIDEBAR_STORAGE_KEY = 'cc.admin-sidebar.collapsed'
/** 分组展开态默认值（默认展开的分组收进 collapseMap 管理） */
const GROUPS_STORAGE_KEY = 'cc.admin-sidebar.groups'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

/** 经角色过滤后的可见导航分组 */
const groups = computed(() => createNavGroups(auth.role))

// ── 侧栏折叠态（80px 图标态，偏好持久化）──
const collapsed = ref(false)
onMounted(() => {
  try {
    collapsed.value = window.localStorage.getItem(SIDEBAR_STORAGE_KEY) === '1'
  } catch {
    /* 隐私模式等场景按展开处理 */
  }
})
/** 写入折叠态并持久化（存储异常静默降级，不影响交互） */
function setCollapsed(next: boolean) {
  collapsed.value = next
  try {
    window.localStorage.setItem(SIDEBAR_STORAGE_KEY, next ? '1' : '0')
  } catch {
    /* 同上 */
  }
}
/** 折叠钮切换：aria-label 随状态在 收起侧栏/展开侧栏 间切换（无障碍契约，测试依赖） */
function toggleCollapsed() {
  setCollapsed(!collapsed.value)
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
/**
 * 分组标题点击入口：折叠态先展开侧栏（设计稿行为——折叠下点分组意图是恢复宽度），
 * 展开态切换手风琴
 */
function onGroupToggle(group: NavGroup) {
  if (collapsed.value) {
    setCollapsed(false)
    return
  }
  toggleGroup(group)
}

/** 分组内是否存在激活项（分组标题联动高亮与指示条定位） */
function isGroupHasActive(group: NavGroup): boolean {
  return group.items.some((item) => isGroupActive(route.path, item.to))
}

// ── 移动端抽屉（≤900px）：hamburger 唤起 + backdrop 关闭；路由跳转自动收起 ──
const mobileOpen = ref(false)
watch(
  () => route.path,
  () => {
    // 抽屉内点导航跳页后收起，避免遮罩残留在新页面
    mobileOpen.value = false
  },
)

// ── 激活指示条：绝对定位 + 弹簧位移（设计稿 nav-indicator，JS 量测 offsetTop/offsetHeight）──
const navRef = ref<HTMLElement | null>(null)
const indicator = ref({ top: 0, height: 0, visible: false })

/**
 * 量测当前激活的顶层导航元素并更新指示条几何
 *
 * 顶层元素（单子项直链/分组标题）挂 data-nav-active 供定位；jsdom 无布局时
 * offsetTop/offsetHeight 恒 0，仅影响视觉不影响逻辑
 */
function syncIndicator(): void {
  const active = navRef.value?.querySelector<HTMLElement>('[data-nav-active="true"]')
  if (!active) {
    indicator.value = { top: 0, height: 0, visible: false }
    return
  }
  indicator.value = { top: active.offsetTop, height: active.offsetHeight, visible: true }
}

/** 折叠宽度/手风琴 max-height 过渡期间指示条落位会漂移：立即量测 + 过渡结束后（.46s）复测 */
function scheduleIndicatorSync(): void {
  void nextTick(syncIndicator)
  window.setTimeout(syncIndicator, 460)
}

watch([() => route.path, collapsed, groupState], scheduleIndicatorSync)
onMounted(syncIndicator)

/** 窗口尺寸变化（含跨过移动端断点）后指示条复测 */
function onResize() {
  syncIndicator()
}
onMounted(() => window.addEventListener('resize', onResize))
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

// ── 路由切换过渡键：页面级身份（见 resolvePageKey 注释，评审修复 I1）──
const pageKey = computed(() => resolvePageKey(route))

// ── 顶栏面包屑：由导航分组定位「分组名 / 页面标题」──
const breadcrumbs = computed(() => {
  const pageTitle = route.meta.title ?? ''
  const group = groups.value.find((g) => g.items.some((item) => isGroupActive(route.path, item.to)))
  if (!group) {
    return [{ label: pageTitle }]
  }
  // 单子项直链分组（如仪表盘）分组名与页面标题同名时去重：
  // 避免「首页 / 仪表盘 / 仪表盘」末两级重复（视觉核对 N9 未决差异 #1）；
  // 其余页面分组名与标题不同（知识库 / 知识库管理），仍渲染两级
  if (group.label === pageTitle) {
    return [{ label: pageTitle }]
  }
  return [{ label: group.label }, { label: pageTitle }]
})

/** 用户菜单角色文案（角色枚举 → 中文展示；未恢复登录态时为空串） */
const roleLabel = computed(() =>
  auth.role === 'SUPER_ADMIN' ? '超级管理员' : auth.role === 'TEACHER' ? '教师' : (auth.role ?? ''),
)

/** 退出登录：登出接口（幂等容错）→ 清理凭据 → 回登录页 */
async function handleLogout() {
  await auth.logout()
  router.push({ name: 'login' })
}

/** 菜单项退出：先收起下拉再执行退出流（close 由 DropdownMenu 作用下发） */
async function onLogoutClick(close: () => void) {
  close()
  await handleLogout()
}
</script>

<template>
  <div class="admin-shell min-h-screen bg-bg text-text">
    <!-- ===== 移动端抽屉遮罩（≤900px 抽屉打开时显现，点击关闭；淡入淡出过渡） ===== -->
    <Transition name="backdrop">
      <div
        v-if="mobileOpen"
        data-testid="drawer-backdrop"
        class="drawer-backdrop"
        @click="mobileOpen = false"
      />
    </Transition>

    <!-- ===== 白色侧栏（固定满高：展开 256px / 折叠 80px，右缘外凸圆形折叠钮） ===== -->
    <aside
      data-testid="admin-sidebar"
      class="sidebar bg-surface"
      :class="{ 'is-collapsed': collapsed, 'mobile-open': mobileOpen }"
    >
      <!-- 折叠钮：右缘外凸 26px 圆钮，hover 缩放、折叠态弹簧旋转 180°（设计稿 A4） -->
      <button
        type="button"
        class="sidebar-toggle"
        :aria-label="collapsed ? '展开侧栏' : '收起侧栏'"
        @click="toggleCollapsed"
      >
        <PhCaretLeft class="size-3.5" weight="bold" />
      </button>

      <!-- 品牌区：书形 logo（双色品牌插画）+ 品牌名；折叠态仅居中 logo，名称淡出 -->
      <div class="brand">
        <svg class="brand-mark" viewBox="0 0 48 48" aria-hidden="true">
          <!-- 紫色书封（跟随品牌令牌，随换肤联动） -->
          <path
            fill="var(--color-brand)"
            d="M24 10.6C20.7 7.9 16.2 6.7 10 6.7c-1 0-1.9.8-1.9 1.9v22.9c0 1 .9 1.9 1.9 1.9 5.5 0 9.6 1.1 12.6 3.4.8.6 1.4.6 2.2 0 3-2.3 7.1-3.4 12.6-3.4 1 0 1.9-.9 1.9-1.9V8.6c0-1.1-.9-1.9-1.9-1.9-6.2 0-10.7 1.2-14 3.9z"
          />
          <!-- 内页橙（设计稿品牌插画强调色，随 logo 定稿固化，非语义色） -->
          <path
            fill="#FFAF45"
            d="M24 13.4c-2.7-2-6.2-3-10.6-3.1v18c4.1.2 7.6 1.1 10.6 2.9 3-1.8 6.5-2.7 10.6-2.9v-18c-4.4.1-7.9 1.1-10.6 3.1z"
          />
          <!-- 页面行线（与表面同色白） -->
          <path
            d="M16.5 15h5M16.5 18.6h5M26.5 15h5M26.5 18.6h5"
            stroke="var(--color-surface)"
            stroke-width="1.6"
            stroke-linecap="round"
            fill="none"
            opacity=".95"
          />
        </svg>
        <span class="brand-name text-text">课程助手管理后台</span>
      </div>

      <!-- 导航分组：激活指示条 + 单子项直链 / 多子项手风琴（角色过滤后渲染） -->
      <nav ref="navRef" class="nav">
        <!-- 激活指示条：4px 圆角品牌紫 + 发光阴影，弹簧位移跟随激活的顶层元素 -->
        <span
          aria-hidden="true"
          class="nav-indicator"
          :style="{
            top: `${indicator.top}px`,
            height: `${indicator.height}px`,
            opacity: !collapsed && indicator.visible ? 1 : 0,
          }"
        />
        <template v-for="group in groups" :key="group.label">
          <!-- 单子项分组：直接渲染链接（仪表盘/课程/反馈） -->
          <RouterLink
            v-if="group.items.length === 1"
            :to="group.items[0].to"
            class="nav-item"
            :class="{ active: isGroupActive(route.path, group.items[0].to) }"
            :data-nav-active="isGroupActive(route.path, group.items[0].to) ? 'true' : undefined"
            :data-tip="group.items[0].label"
          >
            <component :is="group.items[0].icon" class="nav-ic" />
            <span class="nav-label">{{ group.items[0].label }}</span>
          </RouterLink>

          <!-- 多子项分组：手风琴标题 + 子项（知识库/学员/审计） -->
          <template v-else>
            <button
              type="button"
              class="nav-item"
              :class="{ active: isGroupHasActive(group) }"
              :aria-expanded="isGroupOpen(group)"
              data-testid="nav-group-toggle"
              :data-nav-active="isGroupHasActive(group) ? 'true' : undefined"
              :data-tip="group.label"
              @click="onGroupToggle(group)"
            >
              <component :is="group.icon" class="nav-ic" />
              <span class="nav-label">{{ group.label }}</span>
              <PhCaretDown class="nav-chev" :class="{ open: isGroupOpen(group) }" />
            </button>
            <!-- 子项列表：max-height 手风琴（折叠态隐藏；展开态平滑下拉开） -->
            <div
              data-testid="nav-group-children"
              class="sub-menu"
              :class="{ open: isGroupOpen(group) && !collapsed }"
            >
              <RouterLink
                v-for="item in group.items"
                :key="item.to"
                :to="item.to"
                class="sub-link"
                :class="{ active: isGroupActive(route.path, item.to) }"
              >
                {{ item.label }}
              </RouterLink>
            </div>
          </template>
        </template>
      </nav>
    </aside>

    <!-- ===== 主区域（margin-left 随侧栏宽度弹性过渡，移动端归零） ===== -->
    <div class="main-area" :class="{ 'is-collapsed': collapsed }">
      <!-- 顶栏：sticky 毛玻璃 + 面包屑 + 用户下拉；hamburger 仅移动端（≤900px）显示 -->
      <header class="topbar">
        <div class="flex min-w-0 items-center gap-2">
          <button type="button" aria-label="打开菜单" class="hamburger" @click="mobileOpen = true">
            <PhList class="size-5" />
          </button>
          <nav
            aria-label="面包屑"
            class="flex items-center gap-1.5 text-sm"
            data-testid="breadcrumb"
          >
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
        </div>

        <!-- 用户下拉：迁移 N2 DropdownMenu（Esc/外点关闭内建），退出登录流保留 -->
        <DropdownMenu :min-width="208">
          <template #trigger="{ toggle, open }">
            <button
              type="button"
              aria-label="用户菜单"
              :aria-expanded="open"
              class="flex items-center gap-2 rounded-lg px-2 py-1.5 text-sm text-text-muted transition-colors duration-150 hover:bg-surface-2 hover:text-text"
              @click="toggle"
            >
              <span
                class="grid size-7 place-items-center rounded-full bg-gradient-to-br from-brand to-brand-strong text-xs font-bold text-white"
              >
                {{ (auth.displayName ?? '管').charAt(0) }}
              </span>
              <span class="hidden sm:inline">{{ auth.displayName ?? '未登录' }}</span>
              <PhCaretDown class="size-3.5" />
            </button>
          </template>
          <template #default="{ close }">
            <div data-testid="user-menu">
              <div class="border-b border-border px-3 py-2">
                <p class="text-sm font-medium text-text">{{ auth.displayName ?? '' }}</p>
                <p class="mt-0.5 text-xs text-text-muted">{{ roleLabel }}</p>
              </div>
              <div class="mt-1">
                <DropdownMenuItem
                  label="退出登录"
                  tone="danger"
                  aria-label="退出登录"
                  @click="onLogoutClick(close)"
                >
                  <template #icon>
                    <PhSignOut class="size-4" />
                  </template>
                </DropdownMenuItem>
              </div>
            </div>
          </template>
        </DropdownMenu>
      </header>

      <!-- 内容区：统一容器（视图不再自带 main 包裹）。
           key 必须挂在页面 vnode 上（评审修复 I1：原挂 RouterView 致每次导航整树重挂载、
           课程详情壳重复取数）。禁止用路由级 <Transition> 包裹本插槽（已知缺陷，红线 4）：
           当前 vue@3.5.41 + vue-router 组合下导航后新视图永不挂载（真实浏览器实证，无论
           key 取 route.path 还是计算键、是否 mode=out-in 均复现），旧实现把 key 挂
           RouterView 属绕开该缺陷的变通、过渡从未真正播放；待依赖升级后重评（TASK.md §6）。
           容器挂 v-reveal 做首屏入场（指令对 jsdom/减少动效环境自动降级为直接可见） -->
      <main class="min-w-0 flex-1">
        <div v-reveal class="mx-auto w-full max-w-[1400px] px-6 py-6">
          <RouterView v-slot="{ Component: PageComponent }">
            <component :is="PageComponent" :key="pageKey" />
          </RouterView>
        </div>
      </main>
    </div>
  </div>
</template>

<style scoped>
/* ================= 布局壳局部样式（视觉基准：Edukors 设计稿侧栏/顶栏，N3 2026-08-27） ================= */
/* 宽度变量：侧栏展开 256px / 折叠 80px（设计稿 --sw/--swc，随壳根元素级联到侧栏与主区域） */
.admin-shell {
  --sw: 256px;
  --swc: 80px;
}

/* ---- 侧栏：白底固定满高，宽度/位移弹性过渡，入场自左滑入（设计稿 A3/A4） ---- */
.sidebar {
  position: fixed;
  left: 0;
  top: 0;
  bottom: 0;
  z-index: 50;
  display: flex;
  flex-direction: column;
  width: var(--sw);
  box-shadow: 1px 0 0 var(--color-border);
  /* 溢出保持 visible：右缘折叠钮外凸与折叠态 tooltip 均需越出侧栏边界绘制；
     长导航滚动收敛到 .nav 内部（overflow 剪裁会同时裁掉二者，与设计稿形态冲突） */
  overflow: visible;
  transition:
    width 0.45s var(--ease),
    transform 0.45s var(--ease);
  animation: sidebar-slide-in 0.7s var(--ease);
}
.sidebar.is-collapsed {
  width: var(--swc);
}
@keyframes sidebar-slide-in {
  from {
    transform: translateX(-100%);
  }
}

/* ---- 折叠钮：侧栏右缘外凸 26px 圆钮，hover 缩放、折叠态旋转 180°（设计稿 A4） ---- */
.sidebar-toggle {
  position: absolute;
  right: -13px;
  top: 30px;
  width: 26px;
  height: 26px;
  border-radius: 9999px;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  box-shadow: var(--shadow-sm);
  display: grid;
  place-items: center;
  color: var(--color-text-muted);
  z-index: 5;
  transition:
    transform 0.45s var(--spring),
    color 0.2s ease;
}
.sidebar-toggle:hover {
  color: var(--color-brand);
  transform: scale(1.12);
}
.sidebar.is-collapsed .sidebar-toggle {
  transform: rotate(180deg);
}
.sidebar.is-collapsed .sidebar-toggle:hover {
  transform: rotate(180deg) scale(1.12);
}

/* ---- 品牌区：36px 书形 logo + 品牌名（折叠态仅居中 logo，名称淡出收宽） ---- */
.brand {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 22px 22px 16px;
  flex: none;
}
.brand-mark {
  width: 36px;
  height: 36px;
  flex: none;
  filter: drop-shadow(0 4px 8px color-mix(in srgb, var(--color-brand) 35%, transparent));
}
.brand-name {
  /* 中文品牌名 8 字，20px 会贴满 256px 减 logo 后的余量，取 18px/800 留呼吸位 */
  font-size: 18px;
  font-weight: 800;
  letter-spacing: -0.2px;
  white-space: nowrap;
  transition:
    opacity 0.25s ease,
    transform 0.3s var(--ease);
}

/* ---- 导航：激活指示条 + 导航项 + 手风琴子菜单 ---- */
.nav {
  position: relative;
  flex: 1;
  min-height: 0;
  padding: 8px 12px 12px;
  /* 长导航在导航区内部滚动（品牌区与折叠钮固定）；折叠态改 visible 放出 tooltip */
  overflow-y: auto;
  scrollbar-width: none;
}
.nav::-webkit-scrollbar {
  width: 0;
}
.sidebar.is-collapsed .nav {
  overflow: visible;
}

/* 激活指示条：4px 品牌紫圆角条 + 发光阴影，top/height 弹簧位移（设计稿 A6） */
.nav-indicator {
  position: absolute;
  left: 0;
  width: 4px;
  border-radius: 4px;
  background: var(--color-brand);
  box-shadow: var(--shadow-brand-glow);
  transition:
    top 0.45s var(--spring),
    height 0.3s ease,
    opacity 0.3s ease;
}

/* 导航项（单子项直链/分组标题同款）：10px 圆角、muted 静置、hover 浅底、激活紫底紫字 */
.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 11px 12px;
  margin: 2px 0;
  border-radius: 10px;
  color: var(--color-text-muted);
  font-size: 14.5px;
  font-weight: 600;
  text-align: left;
  white-space: nowrap;
  transition:
    background-color 0.25s ease,
    color 0.25s ease;
}
.nav-item .nav-label {
  overflow: hidden;
  text-overflow: ellipsis;
  transition:
    opacity 0.2s ease,
    transform 0.3s var(--ease);
}
.nav-item .nav-ic {
  width: 18px;
  height: 18px;
  flex: none;
  transition: transform 0.3s var(--spring);
}
.nav-item .nav-chev {
  margin-left: auto;
  width: 14px;
  height: 14px;
  flex: none;
  color: var(--color-text-subtle);
  transition: transform 0.35s var(--spring);
}
.nav-item:hover {
  background: var(--color-surface-2);
  color: var(--color-text);
}
.nav-item:hover .nav-ic {
  transform: translateY(-1px) scale(1.1);
}
.nav-item.active {
  background: var(--color-brand-soft);
  color: var(--color-brand);
}
/* 手风琴展开态：chevron 旋转 180°（设计稿 .nav-item.open .chev） */
.nav-chev.open {
  transform: rotate(180deg);
}

/* 子菜单：max-height 手风琴（设计稿 A7：0 → 展开 .4s ease）；激活项紫字加粗 */
.sub-menu {
  max-height: 0;
  overflow: hidden;
  transition: max-height 0.4s var(--ease);
}
/* 180px 覆盖最多 3 个子项（约 120px）的展开高度，避免 JS 量测 scrollHeight */
.sub-menu.open {
  max-height: 180px;
}
.sub-link {
  display: block;
  padding: 8px 12px 8px 44px;
  margin: 1px 0;
  border-radius: 8px;
  font-size: 13.5px;
  color: var(--color-text-muted);
  white-space: nowrap;
  transition:
    color 0.2s ease,
    background-color 0.2s ease,
    padding-left 0.25s ease;
}
.sub-link:hover {
  color: var(--color-brand);
  background: var(--color-surface-2);
  padding-left: 48px;
}
.sub-link.active {
  color: var(--color-brand);
  font-weight: 700;
}

/* ---- 折叠态：品牌名/文字/箭头淡出收宽、子菜单隐藏、导航项居中（设计稿 A4） ---- */
.sidebar.is-collapsed .brand {
  justify-content: center;
  padding-left: 0;
  padding-right: 0;
}
.sidebar.is-collapsed .brand-name,
.sidebar.is-collapsed .nav-item .nav-label,
.sidebar.is-collapsed .nav-item .nav-chev {
  opacity: 0;
  width: 0;
  overflow: hidden;
  pointer-events: none;
}
.sidebar.is-collapsed .nav-item {
  justify-content: center;
  gap: 0;
  padding: 12px 0;
}
.sidebar.is-collapsed .sub-menu {
  display: none;
}

/* ---- 折叠态 tooltip：data-tip 深底气泡 + 45° 小三角（设计稿 A5） ---- */
.sidebar.is-collapsed .nav-item:hover::after {
  content: attr(data-tip);
  position: absolute;
  left: calc(100% + 16px);
  top: 50%;
  transform: translateY(-50%);
  background: var(--color-text);
  color: var(--color-surface);
  font-size: 12px;
  font-weight: 600;
  padding: 7px 12px;
  border-radius: 8px;
  white-space: nowrap;
  z-index: 99;
  box-shadow: var(--shadow-lg);
  animation: tip-in 0.3s var(--spring);
}
.sidebar.is-collapsed .nav-item:hover::before {
  content: '';
  position: absolute;
  left: calc(100% + 9px);
  top: 50%;
  transform: translateY(-50%) rotate(45deg);
  width: 9px;
  height: 9px;
  background: var(--color-text);
  z-index: 98;
  animation: tip-arrow-in 0.3s var(--spring);
}
@keyframes tip-in {
  from {
    opacity: 0;
    transform: translateY(calc(-50% + 6px));
  }
}
@keyframes tip-arrow-in {
  from {
    opacity: 0;
  }
}

/* ---- 主区域：margin-left 随侧栏宽度弹性过渡（设计稿 .main） ---- */
.main-area {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
  min-height: 100vh;
  margin-left: var(--sw);
  transition: margin-left 0.45s var(--ease);
}
.main-area.is-collapsed {
  margin-left: var(--swc);
}

/* ---- 顶栏：sticky 毛玻璃（bg 80% + blur 12px），入场自上淡入（设计稿 A8） ---- */
.topbar {
  position: sticky;
  top: 0;
  z-index: 40;
  height: 64px;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  padding: 0 26px;
  background: color-mix(in srgb, var(--color-bg) 80%, transparent);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  animation: topbar-in 0.6s var(--ease) both;
}
@keyframes topbar-in {
  from {
    opacity: 0;
    transform: translateY(-12px);
  }
}

/* ---- hamburger：仅移动端显示（桌面 display:none，断点见下方媒体查询） ---- */
.hamburger {
  display: none;
  width: 40px;
  height: 40px;
  border-radius: 10px;
  place-items: center;
  color: var(--color-text);
  transition: background-color 0.25s ease;
}
.hamburger:hover {
  background: var(--color-surface);
}

/* ---- 移动端抽屉遮罩：overlay 令牌 + 2px 模糊，淡入淡出 ---- */
.drawer-backdrop {
  position: fixed;
  inset: 0;
  z-index: 45;
  background: var(--color-overlay);
  backdrop-filter: blur(2px);
}
.backdrop-enter-active,
.backdrop-leave-active {
  transition: opacity 0.35s ease;
}
.backdrop-enter-from,
.backdrop-leave-to {
  opacity: 0;
}

/* ---- 移动端（≤900px）：侧栏抽屉化（transform 滑入）+ 折叠态复位为展开形态 ---- */
@media (max-width: 900px) {
  .sidebar {
    transform: translateX(-104%);
  }
  .sidebar.mobile-open {
    transform: none;
  }
  .sidebar .sidebar-toggle {
    display: none;
  }
  .sidebar.is-collapsed {
    width: var(--sw);
  }
  .sidebar.is-collapsed .brand {
    justify-content: flex-start;
    padding: 22px 22px 16px;
  }
  .sidebar.is-collapsed .brand-name,
  .sidebar.is-collapsed .nav-item .nav-label,
  .sidebar.is-collapsed .nav-item .nav-chev {
    opacity: 1;
    width: auto;
    overflow: visible;
    pointer-events: auto;
  }
  .sidebar.is-collapsed .nav-item {
    justify-content: flex-start;
    gap: 12px;
    padding: 11px 12px;
  }
  .sidebar.is-collapsed .sub-menu {
    display: block;
  }
  .main-area,
  .main-area.is-collapsed {
    margin-left: 0;
  }
  .hamburger {
    display: grid;
  }
}
</style>
