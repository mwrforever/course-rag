<script setup lang="ts">
/**
 * 仪表盘页（2026-08-27 紫系换肤重构，设计稿 Edukors Dashboard 形态）
 *
 * 内容区四区块（页头 H1 由布局壳统一渲染）：
 * 1. KPI 行（StatCard 造型：lav 底 + 白底圆形图标 + count-up 数字滚动）：
 *    文档总数 / 待修正分片（amber 图标警示 + 点击跳分片页）/ 学生总数 / 点赞率。
 *    **无环比箭头**（后端无历史对比，设计 D11 禁止假数据；E2E/单测双重负向契约）。
 * 2. 快捷入口条（5 项小卡，quick-* testid 契约不变）。
 * 3. 图表行（1.8fr : 1fr）：反馈趋势 CSS 柱状图（7/30 天切换重拉）+
 *    意图×赞踩堆叠条卡（feedbacks/stats 真实数据）。
 * 4. 底部行（2.15fr : 1fr）：最近上传文档表（eye 按钮跳文档详情）+
 *    反馈意图 donut（三意图占比，SVG 描边生长 + hover 高亮）。
 *
 * 数据源（后端实测契约，PERF-12 拆 core/trend 双查询）：
 * - core 键 = dashboard/stats + feedback/stats?period=today +
 *   documents?sort=created&size=5 + feedbacks/stats 四接口并行（与时间范围无关），
 *   任一失败整页 error 横幅 + 全量重试（页面级错误契约不变）；
 * - trend 键 = feedback/trend?days={7|30} 单独成键，范围切换仅重拉趋势序列
 *   （keepPreviousData 平滑过渡），失败仅趋势区报错重试，不影响其余区块。
 *
 * 设计稿丢弃项（无后端支撑，禁止假数据）：KPI 环比箭头 / Revenue 收入图 /
 * Best Selling 销量表 / 讲师占比环形图原语义（donut 机制复用为意图占比）。
 *
 * 线程安全注意：全部状态为组件私有 ref/computed，无跨实例共享可变状态。
 */
import { computed, ref } from 'vue'
import { keepPreviousData, useQuery } from '@tanstack/vue-query'
import { useRouter } from 'vue-router'
import {
  PhBookOpen,
  PhCalendar,
  PhArrowClockwise,
  PhCaretDown,
  PhChartBar,
  PhEye,
  PhSpinnerGap,
  PhStudent,
  PhThumbsUp,
  PhUploadSimple,
  PhUserPlus,
  PhWarningCircle,
} from '@phosphor-icons/vue'
import type { Component } from 'vue'

import { Badge } from '@/components/ui/badge'
import { DropdownMenu, DropdownMenuItem } from '@/components/ui/dropdown-menu'
import { IconButton } from '@/components/ui/icon-button'
import { StatCard } from '@/components/ui/stat-card'
import IntentDonut from '@/components/charts/IntentDonut.vue'
import IntentLikeBar from '@/components/charts/IntentLikeBar.vue'
import TrendBarChart from '@/components/charts/TrendBarChart.vue'
import { ApiError, dashboardApi, documentApi, feedbackApi } from '@/lib/api'
import { formatDateTime } from '@/lib/utils'

import type { DocumentParseStatus } from '@/lib/types'

const router = useRouter()

// ====================================================================
// 数据加载（PERF-12 拆 core/trend 双查询：core 四接口并行承载页面级三态；
// trend 单独成键随天数换键重拉，分区错误分区重试，互不牵连）
// ====================================================================

/** 趋势时间范围（天）：设计稿 A17 三档裁剪为项目后端实际支撑的两档 */
const trendDays = ref<7 | 30>(7)

/** 时间范围选项（下拉菜单项文案） */
const rangeOptions: Array<{ days: 7 | 30; label: string }> = [
  { days: 7, label: '近 7 天' },
  { days: 30, label: '近 30 天' },
]

/** 当前范围文案（触发按钮展示） */
const rangeLabel = computed(() => (trendDays.value === 7 ? '近 7 天' : '近 30 天'))

/**
 * 切换时间范围：trendDays 变更仅使 trend 查询键变化重拉趋势序列（core 查询零重拉）
 *
 * @param days 目标天数（7 或 30，用户点击下拉选项触发）
 * @param close 下拉菜单关闭回调（DropdownMenu 作用域插槽下发）
 */
function selectRange(days: 7 | 30, close: () => void) {
  trendDays.value = days
  close()
}

/** 接口错误分级文案（与登录页 messageOf 同构；503 统一降级） */
function messageOf(err: unknown): string {
  if (err instanceof ApiError) {
    return err.code === 503 ? '服务暂时不可用，请稍后重试' : err.message
  }
  return '仪表盘加载失败，请稍后重试'
}

/**
 * 仪表盘核心数据查询（core 键，PERF-12 拆键）
 *
 * KPI / 意图统计 / 最近文档四接口均与时间范围无关，合并为单一 core 查询：
 * 全部成功才进入正常态，任一失败即整页 error 横幅 + 全量重试（页面级错误契约保留）。
 * 查询键不含天数——范围切换时本查询零重拉，KPI count-up 与文档表不再闪重载。
 */
const {
  data: coreData,
  isLoading,
  isFetching: coreFetching,
  isError,
  error: queryError,
  refetch: refetchCore,
} = useQuery({
  queryKey: ['dashboard-core'],
  queryFn: async () => {
    const [s, f, docs, intents] = await Promise.all([
      dashboardApi.stats(),
      dashboardApi.feedbackStats('today'),
      documentApi.list({ sort: 'created', size: 5 }),
      feedbackApi.stats(),
    ])
    return {
      stats: s,
      feedback: f,
      recentDocs: docs.records ?? [],
      // 意图统计（donut 与堆叠条共用，likedCount/dislikedCount 为 Long 字符串）
      intents: intents ?? [],
    }
  },
})

/**
 * 反馈趋势查询（trend 键，PERF-12 拆键：唯一随时间范围变化的接口单独成键）
 *
 * queryKey 带 days（7/30 切换即换键重拉，与 core 查询互不影响）；
 * placeholderData 取 keepPreviousData——换键重拉期间沿用上一档序列，
 * 图表不闪空、切换观感平滑。
 * 失败不升页面级横幅，仅在趋势卡内分区报错（见 trendError / trend-retry）。
 */
const {
  data: trendData,
  isLoading: trendLoading,
  isFetching: trendFetching,
  isError: trendIsError,
  error: trendQueryError,
  refetch: refetchTrend,
} = useQuery({
  queryKey: computed(() => ['dashboard-trend', trendDays.value]),
  queryFn: () => dashboardApi.feedbackTrend(trendDays.value),
  placeholderData: keepPreviousData,
})

/** KPI 与图表数据源：全部由查询结果派生（core 四区块 + 趋势各取各的查询） */
const stats = computed(() => coreData.value?.stats ?? null)
const feedback = computed(() => coreData.value?.feedback ?? null)
const recentDocs = computed(() => coreData.value?.recentDocs ?? [])
const intents = computed(() => coreData.value?.intents ?? [])
// count 为 Long 字符串，图表组件内转 number（排序保持接口升序）
const trend = computed(() => trendData.value ?? [])

/** 整页加载失败横幅文案（仅由 core 查询错误驱动；趋势失败分区收敛不升整页） */
const listError = computed(() => (isError.value ? messageOf(queryError.value) : ''))

/** 趋势分区错误文案（仅趋势卡内透出，KPI/文档/意图区块不受影响） */
const trendError = computed(() => (trendIsError.value ? messageOf(trendQueryError.value) : ''))

/** 页头刷新钮加载态：任一分区在拉即反馈（core 或 trend 任一 isFetching） */
const anyFetching = computed(() => coreFetching.value || trendFetching.value)

/**
 * 整页重试/手动刷新：core 与 trend 双查询并行重拉
 *
 * 页面级错误横幅的「重试重新拉取全量数据」语义与页头刷新钮共用本入口；
 * 趋势分区自己的重试走 refetchTrend（不牵动 core）。
 */
function refetchAll() {
  void Promise.all([refetchCore(), refetchTrend()])
}

// ====================================================================
// KPI 数值（count-up 目标；计数全为 Long 字符串转 number，缺数据回退占位 '-'）
// ====================================================================

/** 文档总数（dashboard/stats.documentCount） */
const documentCountValue = computed(() => (stats.value ? Number(stats.value.documentCount) : '-'))
/** 待修正分片数（dashboard/stats.pendingChunkCount，amber 警示口径） */
const pendingChunkValue = computed(() =>
  stats.value ? Number(stats.value.pendingChunkCount) : '-',
)
/** 学生总数（feedback/stats?period=today.studentCount） */
const studentCountValue = computed(() =>
  feedback.value ? Number(feedback.value.studentCount) : '-',
)
/** 点赞率（feedback/stats.likeRate 0~1 double → 整数百分比；StatCard format 补 '%'） */
const likeRateValue = computed(() => {
  const rate = feedback.value?.likeRate
  return rate === undefined ? '-' : Math.round(rate * 100)
})

// ====================================================================
// 文档状态可视化（设计 §2.5 八态体系映射，与文档管理页同口径）
// ====================================================================

/**
 * ETL 状态 → Badge 语义变体
 *
 * PENDING 中性 / PARSING、PARSED 蓝 / CHUNKING、CHUNKED 紫 /
 * EMBEDDING amber / INDEXED emerald 终态 / FAILED red 终态。
 *
 * @param status 文档解析状态
 * @returns Badge variant 名
 */
function statusVariant(status: DocumentParseStatus) {
  switch (status) {
    case 'PARSING':
    case 'PARSED':
      return 'brand'
    case 'CHUNKING':
    case 'CHUNKED':
      return 'violet'
    case 'EMBEDDING':
      return 'warning'
    case 'INDEXED':
      return 'success'
    case 'FAILED':
      return 'danger'
    default:
      return 'default'
  }
}

/** 工作态判定：非终态 Badge 内置 12px spinner（设计 §2.5） */
function isProcessing(status: DocumentParseStatus): boolean {
  return status === 'PARSING' || status === 'CHUNKING' || status === 'EMBEDDING'
}

// ====================================================================
// 快捷入口（quick-* testid 契约冻结：E2E dashboard.spec 与单测共用）
// ====================================================================

/** 快捷入口项（icon 组件经 template 渲染，跳转目标同路由表） */
interface QuickEntry {
  testid: string
  label: string
  desc: string
  to: string
  icon: Component
  /** 图标色（待修正入口保留 amber 警示语义，其余主紫） */
  iconClass: string
}

const quickEntries: QuickEntry[] = [
  {
    testid: 'quick-upload',
    label: '上传文档',
    desc: '入库新资料',
    to: '/knowledge/documents',
    icon: PhUploadSimple,
    iconClass: 'text-brand',
  },
  {
    testid: 'quick-chunks',
    label: '待修正分片',
    desc: '修正质量欠佳分片',
    to: '/knowledge/chunks',
    icon: PhWarningCircle,
    iconClass: 'text-warning',
  },
  {
    testid: 'quick-course',
    label: '新建课程',
    desc: '创建新的课程',
    to: '/courses/new',
    icon: PhBookOpen,
    iconClass: 'text-brand',
  },
  {
    testid: 'quick-students',
    label: '添加学生',
    desc: '开通学生账号',
    to: '/students',
    icon: PhUserPlus,
    iconClass: 'text-brand',
  },
  {
    testid: 'quick-feedback',
    label: '反馈报表',
    desc: '查看赞踩统计',
    to: '/feedback',
    icon: PhChartBar,
    iconClass: 'text-brand',
  },
]
</script>

<template>
  <!-- 加载态：骨架屏与最终布局同形（KPI 灰块 + 入口灰块 + 图表灰块 + 底部灰块，设计 §1.7） -->
  <div
    v-if="isLoading"
    data-testid="dashboard-skeleton"
    class="space-y-6"
    aria-label="仪表盘加载中"
  >
    <div class="grid grid-cols-2 gap-[22px] xl:grid-cols-4">
      <div v-for="i in 4" :key="`kpi-${i}`" class="h-28 animate-pulse rounded-2xl bg-surface-2" />
    </div>
    <div class="grid grid-cols-2 gap-4 sm:grid-cols-3 xl:grid-cols-5">
      <div v-for="i in 5" :key="`quick-${i}`" class="h-28 animate-pulse rounded-2xl bg-surface-2" />
    </div>
    <div class="grid gap-6 xl:grid-cols-[1.8fr_1fr]">
      <div class="h-80 animate-pulse rounded-2xl bg-surface-2" />
      <div class="h-80 animate-pulse rounded-2xl bg-surface-2" />
    </div>
    <div class="grid gap-6 xl:grid-cols-[2.15fr_1fr]">
      <div class="h-72 animate-pulse rounded-2xl bg-surface-2" />
      <div class="h-72 animate-pulse rounded-2xl bg-surface-2" />
    </div>
  </div>

  <!-- 错误态：页内横幅（danger-soft 底）+ 重试（设计 §1.7），重试重新拉取全量数据 -->
  <div
    v-else-if="listError"
    role="alert"
    class="flex items-center justify-between gap-4 rounded-xl border border-danger/30 bg-danger/5 px-4 py-3"
  >
    <span class="text-sm text-danger">{{ listError }}</span>
    <button
      type="button"
      data-testid="retry"
      class="shrink-0 rounded-lg border border-border bg-surface px-3 py-1.5 text-sm text-text transition-colors duration-150 hover:bg-surface-2"
      @click="refetchAll"
    >
      重试
    </button>
  </div>

  <!-- 正常态 -->
  <template v-else>
    <!-- 手动刷新（T2.3）：仪表盘页头 H1 由布局壳渲染，刷新钮置于内容区右上（refetchAll 期间禁用防重复） -->
    <div class="flex justify-end" data-testid="dashboard-refresh-row">
      <IconButton
        label="刷新"
        data-testid="refresh-dashboard"
        :loading="anyFetching"
        @click="refetchAll"
      >
        <PhArrowClockwise class="h-4 w-4" />
      </IconButton>
    </div>
    <!-- KPI 行：StatCard 造型（lav 底 + 白底圆形图标 + count-up），无环比箭头 -->
    <div class="grid grid-cols-2 gap-[22px] xl:grid-cols-4">
      <StatCard
        data-testid="kpi-documents"
        label="文档总数"
        :value="documentCountValue"
        count-up
        tone="brand"
      >
        <template #icon><PhBookOpen class="h-[21px] w-[21px]" weight="duotone" /></template>
      </StatCard>
      <!-- 待修正分片：amber 图标警示 + 点击直达分片修正工作台（无环比，仅绝对值警示） -->
      <button
        type="button"
        data-testid="kpi-pending"
        class="block w-full rounded-2xl text-left focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-warning"
        @click="router.push('/knowledge/chunks')"
      >
        <StatCard label="待修正分片" :value="pendingChunkValue" count-up tone="warning">
          <template #icon><PhWarningCircle class="h-[21px] w-[21px]" weight="duotone" /></template>
          <template #meta><span class="text-text-muted">点击进入修正工作台</span></template>
        </StatCard>
      </button>
      <StatCard
        data-testid="kpi-students"
        label="学生总数"
        :value="studentCountValue"
        count-up
        tone="success"
      >
        <template #icon><PhStudent class="h-[21px] w-[21px]" weight="duotone" /></template>
      </StatCard>
      <StatCard
        data-testid="kpi-like"
        label="点赞率"
        :value="likeRateValue"
        count-up
        tone="danger"
        :format="(v: number) => `${v}%`"
      >
        <template #icon><PhThumbsUp class="h-[21px] w-[21px]" weight="duotone" /></template>
      </StatCard>
    </div>

    <!-- 快捷入口条：5 项横排小卡（图标块 + 标题 + 描述，hover 紫描边浮起，quick-* testid 契约冻结） -->
    <div class="mt-6 grid grid-cols-2 gap-4 sm:grid-cols-3 xl:grid-cols-5">
      <button
        v-for="entry in quickEntries"
        :key="entry.testid"
        type="button"
        :data-testid="entry.testid"
        class="group rounded-2xl border border-border bg-surface p-4 text-left shadow-xs transition-all duration-300 hover:-translate-y-1 hover:border-brand hover:shadow-brand-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand"
        @click="router.push(entry.to)"
      >
        <span
          class="grid h-10 w-10 place-items-center rounded-xl bg-brand-soft transition-transform duration-300 group-hover:scale-110"
          :class="entry.iconClass"
        >
          <component :is="entry.icon" class="h-5 w-5" />
        </span>
        <p
          class="mt-2.5 text-sm font-semibold text-text transition-colors duration-150 group-hover:text-brand-strong"
        >
          {{ entry.label }}
        </p>
        <p class="mt-0.5 text-xs text-text-muted">{{ entry.desc }}</p>
      </button>
    </div>

    <!-- 图表行（1.8fr : 1fr，设计稿 charts-grid）：反馈趋势柱状 + 意图×赞踩堆叠条 -->
    <div class="mt-6 grid gap-6 xl:grid-cols-[1.8fr_1fr]">
      <!-- 反馈趋势卡：7/30 天范围切换仅 trend 查询换键重拉（core 区零感知），柱形 CSS 自绘 -->
      <section class="dash-card rounded-2xl border border-border bg-surface p-6 shadow-xs">
        <div class="flex flex-wrap items-center gap-4">
          <h2 class="mr-auto text-lg font-extrabold text-text">反馈趋势</h2>
          <!-- 时间范围下拉（设计稿 A17 select-dd 形态，N2 DropdownMenu 承载） -->
          <DropdownMenu :min-width="140">
            <template #trigger="{ toggle }">
              <button
                type="button"
                data-testid="trend-range"
                class="flex items-center gap-2 rounded-lg border border-border bg-surface px-3.5 py-2 text-[13.5px] font-semibold text-text transition-colors duration-200 hover:border-brand-strong hover:shadow-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand"
                @click="toggle()"
              >
                <PhCalendar class="h-4 w-4 text-text-muted" />
                {{ rangeLabel }}
                <PhCaretDown class="h-3.5 w-3.5 text-text-muted" />
              </button>
            </template>
            <template #default="{ close }">
              <DropdownMenuItem
                v-for="opt in rangeOptions"
                :key="opt.days"
                :label="opt.label"
                :data-testid="`range-opt-${opt.days}`"
                @click="selectRange(opt.days, close)"
              />
            </template>
          </DropdownMenu>
        </div>
        <!-- 柱状图分区四态：错误（仅本区报错重试，不升整页横幅）/ 初次加载脉冲占位 /
             序列渲染（换键重拉期间降透明度提示刷新）/ 空态 -->
        <div
          class="mt-5 h-[240px] transition-opacity duration-200"
          :class="trendFetching ? 'opacity-60' : ''"
        >
          <!-- 趋势分区错误：danger-soft 内联横幅 + 分区重试（refetchTrend 不牵动 core） -->
          <div
            v-if="trendError"
            data-testid="trend-error"
            role="alert"
            class="flex h-full flex-col items-center justify-center gap-3"
          >
            <p class="text-sm text-danger">{{ trendError }}</p>
            <button
              type="button"
              data-testid="trend-retry"
              class="rounded-lg border border-border bg-surface px-3 py-1.5 text-sm text-text transition-colors duration-150 hover:bg-surface-2"
              @click="() => refetchTrend()"
            >
              重试
            </button>
          </div>
          <!-- 初次加载（尚无序列可展示）：脉冲占位，避免误显「暂无反馈记录」空态 -->
          <div
            v-else-if="trendLoading"
            data-testid="trend-loading"
            class="h-full animate-pulse rounded-xl bg-surface-2"
          />
          <TrendBarChart v-else-if="trend.length > 0" :items="trend" />
          <div v-else class="flex h-full items-center justify-center">
            <p class="text-sm text-text-muted">近 {{ trendDays }} 日暂无反馈记录</p>
          </div>
        </div>
      </section>

      <!-- 意图×赞踩堆叠条卡：feedbacks/stats 真实数据（赞绿/踩红状态语义色） -->
      <section class="dash-card rounded-2xl border border-border bg-surface p-6 shadow-xs">
        <h2 class="text-lg font-extrabold text-text">意图 × 赞踩</h2>
        <div class="mt-4 h-[240px]">
          <IntentLikeBar v-if="intents.length > 0" :stats="intents" />
          <div v-else class="flex h-full items-center justify-center">
            <p class="text-sm text-text-muted">暂无意图统计</p>
          </div>
        </div>
      </section>
    </div>

    <!-- 底部行（2.15fr : 1fr，设计稿 bottom-grid）：最近上传文档表 + 反馈意图 donut -->
    <div class="mt-6 grid gap-6 xl:grid-cols-[2.15fr_1fr]">
      <!-- 最近上传文档：5 行小表 + eye 按钮跳文档详情（设计稿 .eye-btn 形态） -->
      <section
        class="dash-card overflow-hidden rounded-2xl border border-border bg-surface shadow-xs"
      >
        <div class="flex items-center justify-between px-6 pt-6 pb-4">
          <h2 class="text-lg font-extrabold text-text">最近上传文档</h2>
        </div>
        <table v-if="recentDocs.length > 0" data-testid="recent-docs" class="w-full text-sm">
          <thead class="text-left text-[13.5px] font-semibold text-text-muted">
            <tr>
              <th class="bg-surface-2 py-3.5 pr-4 pl-6 first:rounded-l-[10px]">文件名</th>
              <th class="bg-surface-2 px-4 py-3.5 font-semibold">状态</th>
              <th class="bg-surface-2 px-4 py-3.5 text-right font-semibold">上传时间</th>
              <th class="last:rounded-r-[10px] bg-surface-2 px-4 py-3.5 text-right font-semibold">
                操作
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="doc in recentDocs"
              :key="doc.id"
              class="h-[52px] border-b border-border last:border-b-0 transition-colors duration-150 hover:bg-surface-2"
            >
              <td
                class="max-w-[240px] truncate pr-4 pl-6 font-semibold text-text"
                :title="doc.title"
              >
                {{ doc.title }}
              </td>
              <td class="px-4">
                <Badge :variant="statusVariant(doc.parseStatus)">
                  <PhSpinnerGap v-if="isProcessing(doc.parseStatus)" class="h-3 w-3 animate-spin" />
                  {{ doc.parseStatus }}
                </Badge>
              </td>
              <td class="px-4 text-right tabular-nums text-text-muted">
                {{ formatDateTime(doc.createdAt) }}
              </td>
              <td class="px-4 text-right">
                <!-- eye 按钮：跳转既有文档详情路由（/knowledge/documents/:id） -->
                <button
                  type="button"
                  :data-testid="`doc-eye-${doc.id}`"
                  :aria-label="`查看文档详情：${doc.title}`"
                  class="eye-btn inline-grid h-[38px] w-[38px] place-items-center rounded-full bg-brand-soft text-text transition-transform duration-300 hover:bg-brand hover:text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand"
                  @click="router.push(`/knowledge/documents/${doc.id}`)"
                >
                  <PhEye class="h-[17px] w-[17px]" />
                </button>
              </td>
            </tr>
          </tbody>
        </table>
        <!-- 区块空态：一句话 + 行动引导（设计 §1.7，禁裸「暂无数据」） -->
        <div v-else class="px-6 pb-8 text-center">
          <p class="pt-4 text-sm text-text-muted">暂无上传文档</p>
          <p class="mt-1 text-xs text-text-subtle">从快捷入口「上传文档」开始入库</p>
        </div>
      </section>

      <!-- 反馈意图 donut：三意图占比（真实 stats 数据，SVG 描边生长 + hover 高亮） -->
      <section class="dash-card rounded-2xl border border-border bg-surface p-6 shadow-xs">
        <h2 class="text-lg font-extrabold text-text">反馈意图分布</h2>
        <div class="mt-4 h-[240px]">
          <IntentDonut v-if="intents.length > 0" :stats="intents" />
          <div v-else class="flex h-full items-center justify-center">
            <p class="text-sm text-text-muted">暂无意图统计</p>
          </div>
        </div>
      </section>
    </div>
  </template>
</template>

<style scoped>
/* 数据卡 hover：轻浮起 + 挂起阴影（设计稿 .card:hover，曲线走令牌） */
.dash-card {
  transition:
    transform 0.35s var(--ease),
    box-shadow 0.35s ease;
}
.dash-card:hover {
  transform: translateY(-3px);
  box-shadow: var(--shadow-md);
}
/* eye 按钮 hover：主紫底白字 + 旋转放大（设计稿 A21 .eye-btn:hover） */
.eye-btn:hover {
  transform: scale(1.15) rotate(6deg);
}
.eye-btn:active {
  transform: scale(0.9);
}
</style>
