<script setup lang="ts">
/**
 * 仪表盘页（设计 §2.4.1）
 *
 * 内容区四区块（页头 H1 由布局壳统一渲染）：
 * 1. KPI 行（4 卡 grid-cols-4）：文档总数 / 待修正分片（amber 警示，点击跳分片页）/
 *    学生总数 / 点赞率（likeRate 0~1 转百分比）。**无环比箭头**（后端无历史对比，
 *    设计 D11 禁止假数据，仅绝对值 + 待修正警示）。
 * 2. 快捷入口条（5 项横排小卡）：上传文档 / 待修正分片 / 新建课程 / 添加学生 / 反馈报表。
 * 3. 双栏：最近上传文档（5 行小表：文件名/状态 Badge/时间）+ 反馈趋势单折线
 *    （vue-echarts 按需 Line 注册 + canvas 渲染，主题色走 design tokens CSS 变量）。
 *
 * 数据源（后端实测契约）：dashboard/stats（计数全 string）+ feedback/stats?period=today
 * （studentCount string、likeRate double）+ feedback/trend?days=7（count string）+
 * documents?sort=created&size=5。四接口并行拉取，任一失败整页 error 横幅 + 重试。
 */
import { computed } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { useRouter } from 'vue-router'
import { use } from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import VChart from 'vue-echarts'
import {
  PhBookOpen,
  PhChartBar,
  PhSpinnerGap,
  PhUploadSimple,
  PhUserPlus,
  PhWarningCircle,
} from '@phosphor-icons/vue'

import { Badge } from '@/components/ui/badge'
import { ApiError, dashboardApi, documentApi } from '@/lib/api'
import { formatDateTime } from '@/lib/utils'

import type { EChartsCoreOption } from 'echarts/core'
import type { DocumentParseStatus } from '@/lib/types'

// ---- ECharts 按需注册（Line + Grid + Tooltip + canvas 渲染，任务 brief 定案） ----
use([LineChart, GridComponent, TooltipComponent, CanvasRenderer])

const router = useRouter()

// ====================================================================
// 数据加载（并行四接口，成功态/错误态/加载态三态页面级收敛；vue-query 合并单查询）
// ====================================================================

/** 接口错误分级文案（与登录页 messageOf 同构） */
function messageOf(err: unknown): string {
  if (err instanceof ApiError) {
    return err.code === 503 ? '服务暂时不可用，请稍后重试' : err.message
  }
  return '仪表盘加载失败，请稍后重试'
}

/**
 * 仪表盘全量数据（四接口并行，全部成功才进入正常态；失败任一条目即整页 error 横幅）
 *
 * 查询键稳定（无筛选/分页），挂载即拉取；分区块空态（无文档/无趋势）在成功后按区块收敛。
 */
const {
  data,
  isLoading,
  isError,
  error: queryError,
  refetch,
} = useQuery({
  queryKey: ['admin-dashboard-stats'],
  queryFn: async () => {
    const [s, f, t, docs] = await Promise.all([
      dashboardApi.stats(),
      dashboardApi.feedbackStats('today'),
      dashboardApi.feedbackTrend(7),
      documentApi.list({ sort: 'created', size: 5 }),
    ])
    return {
      stats: s,
      feedback: f,
      // count 为 Long 字符串，图表序列在 option 内转 number（排序保持接口升序）
      trend: t ?? [],
      recentDocs: docs.records ?? [],
    }
  },
})

/** KPI 与图表数据源：全部由查询结果派生 */
const stats = computed(() => data.value?.stats ?? null)
const feedback = computed(() => data.value?.feedback ?? null)
const trend = computed(() => data.value?.trend ?? [])
const recentDocs = computed(() => data.value?.recentDocs ?? [])

/** 整页加载失败横幅文案（queryError 非空时透出；503 统一降级） */
const listError = computed(() => (isError.value ? messageOf(queryError.value) : ''))

// ====================================================================
// KPI 与点赞率
// ====================================================================

/** 点赞率：likeRate 0~1 double → 百分比整数展示（如 0.86 → 86%） */
const likeRateText = computed(() => {
  const rate = feedback.value?.likeRate
  return rate === undefined ? '-' : `${Math.round(rate * 100)}%`
})

// ====================================================================
// 反馈趋势单折线（vue-echarts，主题色读 design tokens CSS 变量）
// ====================================================================

/**
 * 解析 CSS 变量为图表颜色（echarts 主题跟随 tokens）
 *
 * jsdom 测试环境无 @theme 样式注入时 getPropertyValue 返回空串，回退令牌十六进制
 * （与 main.css @theme 定义一致），保证浏览器与测试色彩契约一致。
 *
 * @param varName CSS 变量名（如 --color-brand）
 * @param fallback 变量不可用时的令牌十六进制
 * @returns 图表可用颜色串
 */
function tokenColor(varName: string, fallback: string): string {
  const value = getComputedStyle(document.documentElement).getPropertyValue(varName).trim()
  return value || fallback
}

/** 折线主色：brand 蓝 600（--color-brand） */
const lineColor = tokenColor('--color-brand', '#2563EB')
/** 面积填充色：brand-soft 蓝 100（--color-brand-soft） */
const areaColor = tokenColor('--color-brand-soft', '#DBEAFE')
/** 轴刻度线色（--color-chart-axis，@theme 语义令牌） */
const axisColor = tokenColor('--color-chart-axis', '#E2E8F0')
/** 网格分隔线色（--color-chart-grid） */
const gridColor = tokenColor('--color-chart-grid', '#F1F5F9')
/** 轴标签/图例文字色（--color-chart-label） */
const labelColor = tokenColor('--color-chart-label', '#64748B')
/** 数据点描边色（--color-chart-point-border，与卡片底同色） */
const pointBorder = tokenColor('--color-chart-point-border', '#FFFFFF')

/**
 * 单折线 option：近 7 日每日反馈数（无赞踩分列，设计 G7）
 *
 * x 轴取日期 MM-DD，序列数据 count 字符串转 number；grid/tooltip 按需注册。
 */
const chartOption = computed<EChartsCoreOption>(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 8, right: 16, top: 24, bottom: 8, containLabel: true },
  xAxis: {
    type: 'category',
    boundaryGap: false,
    data: trend.value.map((t) => t.date.slice(5)),
    axisLine: { lineStyle: { color: axisColor } },
    axisTick: { show: false },
    axisLabel: { color: labelColor, fontSize: 12 },
  },
  yAxis: {
    type: 'value',
    // 反馈数为整数，最小间隔 1 防小数刻度
    minInterval: 1,
    splitLine: { lineStyle: { color: gridColor } },
    axisLabel: { color: labelColor, fontSize: 12 },
  },
  series: [
    {
      name: '每日反馈数',
      type: 'line',
      data: trend.value.map((t) => Number(t.count)),
      smooth: true,
      symbol: 'circle',
      symbolSize: 6,
      lineStyle: { color: lineColor, width: 2 },
      itemStyle: { color: lineColor, borderColor: pointBorder, borderWidth: 1 },
      areaStyle: { color: areaColor, opacity: 0.5 },
    },
  ],
}))

// ====================================================================
// 文档状态可视化（设计 §2.5 八态体系映射）
// ====================================================================

/**
 * ETL 状态 → Badge 语义变体（设计 §2.5 明细）
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

/** 快捷入口项（icon 组件注入 template，跳转目标同路由表） */
interface QuickEntry {
  testid: string
  label: string
  desc: string
  to: string
}

const quickEntries: QuickEntry[] = [
  { testid: 'quick-upload', label: '上传文档', desc: '入库新资料', to: '/knowledge/documents' },
  {
    testid: 'quick-chunks',
    label: '待修正分片',
    desc: '修正质量欠佳分片',
    to: '/knowledge/chunks',
  },
  { testid: 'quick-course', label: '新建课程', desc: '创建新的课程', to: '/courses/new' },
  { testid: 'quick-students', label: '添加学生', desc: '开通学生账号', to: '/students' },
  { testid: 'quick-feedback', label: '反馈报表', desc: '查看赞踩统计', to: '/feedback' },
]
</script>

<template>
  <!-- 加载态：骨架屏与最终布局同形（KPI 灰块 + 入口灰块 + 双栏灰块，设计 §1.7） -->
  <div
    v-if="isLoading"
    data-testid="dashboard-skeleton"
    class="space-y-4"
    aria-label="仪表盘加载中"
  >
    <div class="grid grid-cols-4 gap-4">
      <div v-for="i in 4" :key="`kpi-${i}`" class="h-20 animate-pulse rounded-xl bg-surface-2" />
    </div>
    <div class="grid grid-cols-5 gap-4">
      <div v-for="i in 5" :key="`quick-${i}`" class="h-24 animate-pulse rounded-xl bg-surface-2" />
    </div>
    <div class="grid gap-4 xl:grid-cols-2">
      <div class="h-72 animate-pulse rounded-xl bg-surface-2" />
      <div class="h-72 animate-pulse rounded-xl bg-surface-2" />
    </div>
  </div>

  <!-- 错误态：页内横幅（danger-soft 底）+ 重试（设计 §1.7），重试重新拉取全量数据 -->
  <div
    v-else-if="listError"
    role="alert"
    class="flex items-center justify-between gap-4 rounded-lg border border-danger/30 bg-red-50 px-4 py-3"
  >
    <span class="text-sm text-danger">{{ listError }}</span>
    <button
      type="button"
      data-testid="retry"
      class="shrink-0 rounded-lg border border-border bg-surface px-3 py-1.5 text-sm text-text transition-colors duration-150 hover:bg-surface-2"
      @click="() => refetch()"
    >
      重试
    </button>
  </div>

  <!-- 正常态 -->
  <template v-else>
    <!-- KPI 行：4 卡（文档总数 / 待修正分片[amber] / 学生总数 / 点赞率），无环比箭头 -->
    <div class="grid grid-cols-4 gap-4">
      <div data-testid="kpi-documents" class="rounded-xl border border-border bg-surface p-4">
        <p class="text-xs font-medium text-text-muted">文档总数</p>
        <p class="mt-2 text-2xl font-bold tabular-nums text-text">
          {{ stats?.documentCount ?? '-' }}
        </p>
      </div>
      <!-- 待修正分片：amber 警示（状态语义），点击直达分片修正工作台 -->
      <button
        type="button"
        data-testid="kpi-pending"
        class="rounded-xl border border-amber-200 bg-amber-50 p-4 text-left transition-colors duration-150 hover:bg-amber-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-warning"
        @click="router.push('/knowledge/chunks')"
      >
        <p class="text-xs font-medium text-warning">待修正分片</p>
        <p class="mt-2 text-2xl font-bold tabular-nums text-warning">
          {{ stats?.pendingChunkCount ?? '-' }}
        </p>
      </button>
      <div data-testid="kpi-students" class="rounded-xl border border-border bg-surface p-4">
        <p class="text-xs font-medium text-text-muted">学生总数</p>
        <p class="mt-2 text-2xl font-bold tabular-nums text-text">
          {{ feedback?.studentCount ?? '-' }}
        </p>
      </div>
      <div data-testid="kpi-like" class="rounded-xl border border-border bg-surface p-4">
        <p class="text-xs font-medium text-text-muted">点赞率</p>
        <p class="mt-2 text-2xl font-bold tabular-nums text-text">{{ likeRateText }}</p>
      </div>
    </div>

    <!-- 快捷入口条：5 项横排小卡（icon + 标题 + 描述，hover 边框强调，设计 §2.4.1） -->
    <div class="mt-4 grid grid-cols-5 gap-4">
      <button
        v-for="entry in quickEntries"
        :key="entry.testid"
        type="button"
        :data-testid="entry.testid"
        class="group rounded-xl border border-border bg-surface p-4 text-left transition-colors duration-150 hover:border-blue-300 hover:bg-surface-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand"
        @click="router.push(entry.to)"
      >
        <PhUploadSimple
          v-if="entry.testid === 'quick-upload'"
          class="h-5 w-5 text-brand"
          weight="duotone"
        />
        <PhWarningCircle
          v-else-if="entry.testid === 'quick-chunks'"
          class="h-5 w-5 text-warning"
          weight="duotone"
        />
        <PhBookOpen v-else-if="entry.testid === 'quick-course'" class="h-5 w-5 text-brand" />
        <PhUserPlus v-else-if="entry.testid === 'quick-students'" class="h-5 w-5 text-brand" />
        <PhChartBar v-else class="h-5 w-5 text-brand" />
        <p
          class="mt-2 text-sm font-medium text-text transition-colors duration-150 group-hover:text-brand-strong"
        >
          {{ entry.label }}
        </p>
        <p class="mt-0.5 text-xs text-text-muted">{{ entry.desc }}</p>
      </button>
    </div>

    <!-- 双栏：最近上传文档（5 行小表）+ 反馈趋势（单折线） -->
    <div class="mt-4 grid gap-4 xl:grid-cols-2">
      <div class="rounded-xl border border-border bg-surface">
        <div class="flex items-center justify-between border-b border-border px-4 py-3">
          <h2 class="text-sm font-semibold text-text">最近上传文档</h2>
        </div>
        <table v-if="recentDocs.length > 0" data-testid="recent-docs" class="w-full text-sm">
          <thead class="border-b border-border text-left text-xs text-text-muted">
            <tr>
              <th class="px-4 py-2 font-medium">文件名</th>
              <th class="px-4 py-2 font-medium">状态</th>
              <th class="px-4 py-2 text-right font-medium">上传时间</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="doc in recentDocs"
              :key="doc.id"
              class="h-11 border-b border-border last:border-b-0 transition-colors duration-150 hover:bg-surface-2"
            >
              <td class="max-w-[240px] truncate px-4 font-medium text-text" :title="doc.title">
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
            </tr>
          </tbody>
        </table>
        <!-- 区块空态：一句话 + 行动引导（设计 §1.7，禁裸「暂无数据」） -->
        <div v-else class="px-4 py-10 text-center">
          <p class="text-sm text-text-muted">暂无上传文档</p>
          <p class="mt-1 text-xs text-text-subtle">从快捷入口「上传文档」开始入库</p>
        </div>
      </div>

      <div class="rounded-xl border border-border bg-surface p-4">
        <div class="flex items-center justify-between">
          <h2 class="text-sm font-semibold text-text">反馈趋势</h2>
          <span class="text-xs text-text-subtle">近 7 日</span>
        </div>
        <!-- 单折线：每日反馈数（vue-echarts canvas 渲染；空趋势降级为区块空态） -->
        <div v-if="trend.length > 0" class="mt-2 h-56">
          <v-chart :option="chartOption" autoresize class="h-full w-full" />
        </div>
        <div v-else class="flex h-56 items-center justify-center">
          <p class="text-sm text-text-muted">近 7 日暂无反馈记录</p>
        </div>
      </div>
    </div>
  </template>
</template>
