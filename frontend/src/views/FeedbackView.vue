<script setup lang="ts">
/**
 * 反馈报表页（设计 §2.4.6）
 *
 * 能力清单：
 * 1. KPI 4 卡：总反馈（列表计数）/ 点赞 / 点踩 / 点赞率（feedbacks/stats 三字段求和，
 *    非空总和为 0 时点赞率显示占位）。**无环比、无课程维度图**（后端无数据，D11 禁止假数据）。
 * 2. 图 1（全宽）单折线：近 7 日每日反馈数（dashboardApi.feedbackTrend，无赞踩分列 G7）
 * 3. 图 2 意图×赞踩堆叠柱状图：stats 的 intentType/likedCount/dislikedCount 三字段
 *    （vue-echarts Bar 堆叠，点赞 success 绿 / 点踩 danger 红）
 * 4. 列表：feedbacks?intentType 筛选 + 分页；列：#id / 用户（#userId 短格式）/
 *    意图 Badge / 赞踩图标 / 时间 / 操作
 * 5. 回放入口角色差异：仅超管可见「查看会话回放」→ Drawer 700px 调 sessionApi.detail
 *    渲染 messages 只读流（role/content/intentType/seq）；教师无回放入口
 * 6. 删除：两角色均可（后端 I3 全局口径），二次确认（danger）
 * 7. 四态：loading 骨架 / empty / error 横幅重试 / 正常
 *
 * 契约要点：likedCount/dislikedCount 为 Long 字符串，图表序列转 number；
 * userId 为 Long 字符串（短格式展示 G10）；时间 ISO-8601 短格式。
 *
 * 线程安全注意：全部状态为组件私有 ref，无跨实例共享可变状态。
 */
import { computed, onMounted, ref } from 'vue'
import { use } from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import VChart from 'vue-echarts'
import { PhSpinnerGap, PhThumbsDown, PhThumbsUp, PhWarningCircle } from '@phosphor-icons/vue'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ApiError, dashboardApi, feedbackApi, sessionApi } from '@/lib/api'
import { showToast } from '@/lib/toast'
import { formatDateTime } from '@/lib/utils'
import { useAuthStore } from '@/stores/auth'

import type { EChartsCoreOption } from 'echarts/core'
import type {
  ChatSessionDetailVO,
  FeedbackIntentStat,
  FeedbackTrendItem,
  UserFeedbackVO,
} from '@/lib/types'

// ---- ECharts 按需注册（Line + Bar + Grid/Tooltip/Legend + canvas 渲染，任务 brief 定案） ----
use([LineChart, BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

/** 每页条数（设计 §2.6 分页器） */
const PAGE_SIZE = 10

/** 意图筛选选项（后端意图体系：knowledge_question / chat / unknown） */
const INTENT_OPTIONS: Array<{ value: string; label: string }> = [
  { value: 'knowledge_question', label: '知识问答' },
  { value: 'chat', label: '闲聊' },
  { value: 'unknown', label: '未知意图' },
]

const auth = useAuthStore()

/** 超管判定：回放入口仅超管可见（设计 §2.4.6 角色差异，教师无回放 Drawer） */
const isAdmin = computed(() => auth.role === 'SUPER_ADMIN')

// ====================================================================
// 数据加载（列表 + 统计 + 趋势并行，四态页面级收敛）
// ====================================================================

const loading = ref(true)
const error = ref('')
const list = ref<UserFeedbackVO[]>([])
const stats = ref<FeedbackIntentStat[]>([])
const trend = ref<FeedbackTrendItem[]>([])
const total = ref('0')
const page = ref(1)
const intentType = ref('')

/** 总页数：total 为 Long 字符串，转 number 后按 PAGE_SIZE 上取整 */
const totalPages = computed(() => Math.max(1, Math.ceil(Number(total.value) / PAGE_SIZE)))

/** 接口错误分级文案（ApiError 透出 message，503 统一降级；非 ApiError 兜底） */
function messageOf(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    return err.code === 503 ? '服务暂时不可用，请稍后重试' : err.message
  }
  return fallback
}

/** 拉取反馈报表全量数据：列表 + 统计 + 趋势三接口并行，失败整页 error 横幅 */
async function load() {
  loading.value = true
  error.value = ''
  try {
    const [res, s, t] = await Promise.all([
      feedbackApi.list({
        page: page.value,
        size: PAGE_SIZE,
        ...(intentType.value ? { intentType: intentType.value } : {}),
      }),
      feedbackApi.stats(),
      dashboardApi.feedbackTrend(7),
    ])
    list.value = res.records ?? []
    total.value = res.total
    stats.value = s ?? []
    trend.value = t ?? []
  } catch (err) {
    error.value = messageOf(err, '反馈报表加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

onMounted(load)

/** 意图筛选变更：重置第 1 页并重新拉取（KPI/图表基于全局 stats 不受影响） */
function onFilterChange(e: Event) {
  intentType.value = (e.target as HTMLSelectElement).value
  page.value = 1
  load()
}

/** 翻页：越界保护 */
function changePage(next: number) {
  if (next < 1 || next > totalPages.value) return
  page.value = next
  load()
}

// ====================================================================
// KPI 4 卡（stats 求和 + 列表计数）
// ====================================================================

/** 点赞总数：stats 各意图 likedCount 求和（Long 字符串 → number） */
const likedTotal = computed(() => stats.value.reduce((acc, s) => acc + Number(s.likedCount), 0))
/** 点踩总数：stats 各意图 dislikedCount 求和 */
const dislikedTotal = computed(() =>
  stats.value.reduce((acc, s) => acc + Number(s.dislikedCount), 0),
)

/** 点赞率：liked/(liked+disliked) 百分比取整；无评价数据时展示占位（防除零） */
const likeRateText = computed(() => {
  const sum = likedTotal.value + dislikedTotal.value
  if (sum === 0) return '-'
  return `${Math.round((likedTotal.value / sum) * 100)}%`
})

// ====================================================================
// 图 1 单折线（每日反馈数，trend）+ 图 2 意图×赞踩堆叠柱状图（stats）
// ====================================================================

/**
 * 解析 CSS 变量为图表颜色（jsdom 无 @theme 注入时回退令牌十六进制，
 * 与 main.css @theme 定义一致（同 dashboard-view 策略））
 *
 * @param varName CSS 变量名（如 --color-brand）
 * @param fallback 变量不可用时的令牌十六进制
 */
function tokenColor(varName: string, fallback: string): string {
  const value = getComputedStyle(document.documentElement).getPropertyValue(varName).trim()
  return value || fallback
}

/** 折线主色：brand 蓝 600；面积填充：brand-soft */
const lineColor = tokenColor('--color-brand', '#2563EB')
const areaColor = tokenColor('--color-brand-soft', '#DBEAFE')
/** 堆叠柱语义色：点赞 success / 点踩 danger（状态语义色，非装饰） */
const likedColor = tokenColor('--color-success', '#16A34A')
const dislikedColor = tokenColor('--color-danger', '#DC2626')

/** 单折线 option：x 轴 MM-DD，序列数据 count 字符串转 number（无赞踩分列 G7） */
const trendOption = computed<EChartsCoreOption>(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 8, right: 16, top: 24, bottom: 8, containLabel: true },
  xAxis: {
    type: 'category',
    boundaryGap: false,
    data: trend.value.map((t) => t.date.slice(5)),
    axisLine: { lineStyle: { color: '#E2E8F0' } },
    axisTick: { show: false },
    axisLabel: { color: '#64748B', fontSize: 12 },
  },
  yAxis: {
    type: 'value',
    minInterval: 1,
    splitLine: { lineStyle: { color: '#F1F5F9' } },
    axisLabel: { color: '#64748B', fontSize: 12 },
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
      itemStyle: { color: lineColor, borderColor: '#FFFFFF', borderWidth: 1 },
      areaStyle: { color: areaColor, opacity: 0.5 },
    },
  ],
}))

/** 意图×赞踩堆叠柱状图 option：x 轴意图枚举，点赞/点踩两序列 stack 同名 */
const statsOption = computed<EChartsCoreOption>(() => ({
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
  legend: { bottom: 0, textStyle: { color: '#64748B', fontSize: 12 } },
  grid: { left: 8, right: 16, top: 24, bottom: 36, containLabel: true },
  xAxis: {
    type: 'category',
    data: stats.value.map((s) => s.intentType),
    axisLine: { lineStyle: { color: '#E2E8F0' } },
    axisTick: { show: false },
    axisLabel: { color: '#64748B', fontSize: 12 },
  },
  yAxis: {
    type: 'value',
    minInterval: 1,
    splitLine: { lineStyle: { color: '#F1F5F9' } },
    axisLabel: { color: '#64748B', fontSize: 12 },
  },
  series: [
    {
      name: '点赞',
      type: 'bar',
      stack: '赞踩',
      itemStyle: { color: likedColor },
      data: stats.value.map((s) => Number(s.likedCount)),
    },
    {
      name: '点踩',
      type: 'bar',
      stack: '赞踩',
      itemStyle: { color: dislikedColor },
      data: stats.value.map((s) => Number(s.dislikedCount)),
    },
  ],
}))

// ====================================================================
// 列表辅助：userId 短格式 / 意图 Badge / 赞踩图标
// ====================================================================

/**
 * Long 字符串 ID 短格式（G10）：超过 8 位截断为前 8 位 + 省略号，
 * 满足数据密集表格的紧凑展示（全量 ID 无业务可读性）
 *
 * @param id Long 序列化字符串 ID（如 9000000001）
 * @returns 短格式展示串
 */
function shortId(id: string): string {
  return id.length > 8 ? `${id.slice(0, 8)}…` : id
}

/** 意图 Badge 变体：knowledge_question 强调 / chat 中性 / unknown 描边 */
function intentVariant(intent: string | null) {
  switch (intent) {
    case 'knowledge_question':
      return 'brand' as const
    case 'chat':
      return 'default' as const
    default:
      return 'outline' as const
  }
}

// ====================================================================
// 会话回放 Drawer（仅超管入口；详细消息只读流 700px）
// ====================================================================

const replayTarget = ref<UserFeedbackVO | null>(null)
const replayDetail = ref<ChatSessionDetailVO | null>(null)
const replayLoading = ref(false)

/** 打开回放 Drawer：调 sessionApi.detail 拉取会话完整消息（只读回放） */
async function openReplay(fb: UserFeedbackVO) {
  replayTarget.value = fb
  replayDetail.value = null
  replayLoading.value = true
  try {
    replayDetail.value = await sessionApi.detail(fb.sessionId)
  } catch (err) {
    showToast(messageOf(err, '会话详情加载失败，请稍后重试'), 'danger')
    replayTarget.value = null
  } finally {
    replayLoading.value = false
  }
}

/** 关闭回放 Drawer：加载中拦截（防丢加载态） */
function closeReplay() {
  if (replayLoading.value) return
  replayTarget.value = null
  replayDetail.value = null
}

// ====================================================================
// 删除（两角色均可，二次确认）
// ====================================================================

const deleting = ref<UserFeedbackVO | null>(null)
const deleteSubmitting = ref(false)

function requestDelete(fb: UserFeedbackVO) {
  deleting.value = fb
}

function cancelDelete() {
  if (deleteSubmitting.value) return
  deleting.value = null
}

/** 确认删除：remove → toast → 关闭确认框 → 刷新 */
async function confirmDelete() {
  if (!deleting.value) return
  deleteSubmitting.value = true
  try {
    await feedbackApi.remove(deleting.value.id)
    showToast('反馈已删除', 'success')
    deleting.value = null
    await load()
  } catch (err) {
    showToast(messageOf(err, '删除失败，请稍后重试'), 'danger')
  } finally {
    deleteSubmitting.value = false
  }
}
</script>

<template>
  <!-- 加载态：骨架屏与最终布局同形（KPI 灰块 + 图表灰块 + 表格灰行） -->
  <div v-if="loading" data-testid="feedback-skeleton" class="space-y-4" aria-label="反馈报表加载中">
    <div class="grid grid-cols-4 gap-4">
      <div v-for="i in 4" :key="`kpi-${i}`" class="h-20 animate-pulse rounded-xl bg-surface-2" />
    </div>
    <div class="h-60 animate-pulse rounded-xl bg-surface-2" />
    <div class="h-40 animate-pulse rounded-xl bg-surface-2" />
    <div class="h-48 animate-pulse rounded-xl bg-surface-2" />
  </div>

  <!-- 错误态：页内横幅 + 重试（设计 §1.7） -->
  <div
    v-else-if="error"
    role="alert"
    class="flex items-center justify-between gap-4 rounded-lg border border-danger/30 bg-red-50 px-4 py-3"
  >
    <span class="text-sm text-danger">{{ error }}</span>
    <Button variant="outline" size="sm" data-testid="retry-feedback" @click="load">重试</Button>
  </div>

  <!-- 正常态 -->
  <template v-else>
    <!-- KPI 行：4 卡（总反馈/点赞/点踩/点赞率），数字域 tabular-nums -->
    <div class="grid grid-cols-4 gap-4">
      <div
        data-testid="kpi-total"
        class="rounded-xl border border-border bg-surface p-4 tabular-nums"
      >
        <p class="text-xs font-medium text-text-muted">总反馈</p>
        <p class="mt-2 text-2xl font-bold tabular-nums text-text">{{ total }}</p>
      </div>
      <div data-testid="kpi-liked" class="rounded-xl border border-border bg-surface p-4">
        <p class="text-xs font-medium text-text-muted">点赞</p>
        <p class="mt-2 text-2xl font-bold tabular-nums text-success">{{ likedTotal }}</p>
      </div>
      <div data-testid="kpi-disliked" class="rounded-xl border border-border bg-surface p-4">
        <p class="text-xs font-medium text-text-muted">点踩</p>
        <p class="mt-2 text-2xl font-bold tabular-nums text-danger">{{ dislikedTotal }}</p>
      </div>
      <div data-testid="kpi-rate" class="rounded-xl border border-border bg-surface p-4">
        <p class="text-xs font-medium text-text-muted">点赞率</p>
        <p class="mt-2 text-2xl font-bold tabular-nums text-text">{{ likeRateText }}</p>
      </div>
    </div>

    <!-- 图 1（全宽）：单折线每日反馈数（trend 空时降级区块空态） -->
    <div class="mt-4 rounded-xl border border-border bg-surface p-4">
      <div class="flex items-center justify-between">
        <h2 class="text-sm font-semibold text-text">每日反馈数</h2>
        <span class="text-xs text-text-subtle">近 7 日</span>
      </div>
      <div v-if="trend.length > 0" class="mt-2 h-56">
        <v-chart :option="trendOption" autoresize class="h-full w-full" />
      </div>
      <div v-else class="flex h-56 items-center justify-center">
        <p class="text-sm text-text-muted">近 7 日暂无反馈记录</p>
      </div>
    </div>

    <!-- 图 2：意图×赞踩堆叠柱状图（stats 空时降级区块空态） -->
    <div class="mt-4 rounded-xl border border-border bg-surface p-4">
      <h2 class="text-sm font-semibold text-text">意图 × 赞踩分布</h2>
      <div v-if="stats.length > 0" class="mt-2 h-48">
        <v-chart :option="statsOption" autoresize class="h-full w-full" />
      </div>
      <div v-else class="flex h-48 items-center justify-center">
        <p class="text-sm text-text-muted">暂无意图统计</p>
      </div>
    </div>

    <!-- 列表区：意图筛选 + 分页表格 -->
    <div class="mt-4 flex items-center gap-2">
      <select
        data-testid="filter-intent"
        aria-label="按意图筛选"
        :value="intentType"
        class="h-9 rounded-lg border border-border bg-surface px-2 text-sm text-text outline-none transition-colors duration-150 focus:border-brand focus:ring-2 focus:ring-brand/20"
        @change="onFilterChange"
      >
        <option value="">全部意图</option>
        <option v-for="opt in INTENT_OPTIONS" :key="opt.value" :value="opt.value">
          {{ opt.label }}
        </option>
      </select>
    </div>

    <div
      v-if="list.length === 0"
      class="mt-4 flex flex-col items-center justify-center rounded-xl border border-dashed border-border bg-surface py-14 text-center"
    >
      <PhWarningCircle class="h-8 w-8 text-text-subtle" />
      <p class="mt-3 text-sm font-medium text-text">还没有反馈记录</p>
      <p class="mt-1 text-xs text-text-muted">学生对话后对 AI 回复进行赞踩评价后汇聚于此</p>
    </div>

    <template v-else>
      <div class="mt-4 overflow-hidden rounded-xl border border-border bg-surface">
        <table data-testid="fb-table" class="w-full text-sm">
          <thead class="border-b border-border bg-surface-2 text-left text-xs text-text-muted">
            <tr>
              <th class="w-20 px-4 py-2.5 font-medium">#id</th>
              <th class="w-32 px-4 py-2.5 font-medium">用户</th>
              <th class="w-40 px-4 py-2.5 font-medium">意图</th>
              <th class="w-20 px-4 py-2.5 font-medium">评价</th>
              <th class="w-32 px-4 py-2.5 font-medium">时间</th>
              <th class="px-4 py-2.5 text-right font-medium">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="fb in list"
              :key="fb.id"
              :data-testid="`row-${fb.id}`"
              class="h-11 border-b border-border last:border-b-0 transition-colors duration-150 hover:bg-surface-2"
            >
              <td class="px-4 tabular-nums text-text-muted">#{{ fb.id }}</td>
              <td :data-testid="`fb-user-${fb.id}`" class="px-4 tabular-nums text-text-muted">
                {{ shortId(fb.userId) }}
              </td>
              <td class="px-4">
                <Badge :data-testid="`fb-intent-${fb.id}`" :variant="intentVariant(fb.intentType)">
                  {{ fb.intentType ?? '未标注' }}
                </Badge>
              </td>
              <td class="px-4">
                <!-- 赞踩图标：Phosphor 线性图标（禁 emoji），语义色表达评价方向 -->
                <span
                  v-if="fb.isLiked === true"
                  :data-testid="`fb-liked-${fb.id}`"
                  class="inline-flex items-center gap-1 text-xs text-success"
                >
                  <PhThumbsUp class="h-4 w-4" weight="fill" />
                  赞
                </span>
                <span
                  v-else-if="fb.isLiked === false"
                  :data-testid="`fb-disliked-${fb.id}`"
                  class="inline-flex items-center gap-1 text-xs text-danger"
                >
                  <PhThumbsDown class="h-4 w-4" weight="fill" />
                  踩
                </span>
                <span v-else class="text-xs text-text-subtle">未评</span>
              </td>
              <td :data-testid="`fb-time-${fb.id}`" class="px-4 tabular-nums text-text-muted">
                {{ formatDateTime(fb.createdAt) }}
              </td>
              <td class="px-4 text-right">
                <div class="flex items-center justify-end gap-1">
                  <!-- 回放入口角色差异：仅超管可见（设计 §2.4.6） -->
                  <Button
                    v-if="isAdmin"
                    variant="ghost"
                    size="sm"
                    :data-testid="`op-replay-${fb.id}`"
                    @click="openReplay(fb)"
                  >
                    查看会话回放
                  </Button>
                  <Button
                    variant="danger"
                    size="sm"
                    :data-testid="`op-delete-${fb.id}`"
                    @click="requestDelete(fb)"
                  >
                    删除
                  </Button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 分页器：左「共 N 条」右 上/下页 + 页码（设计 §2.6） -->
      <div class="mt-4 flex items-center justify-between text-sm text-text-muted">
        <span>
          共 <span class="tabular-nums text-text">{{ total }}</span> 条
        </span>
        <div class="flex items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            data-testid="prev-page"
            :disabled="page <= 1"
            @click="changePage(page - 1)"
          >
            上一页
          </Button>
          <span class="tabular-nums">第 {{ page }} / {{ totalPages }} 页</span>
          <Button
            variant="outline"
            size="sm"
            data-testid="next-page"
            :disabled="page >= totalPages"
            @click="changePage(page + 1)"
          >
            下一页
          </Button>
        </div>
      </div>
    </template>
  </template>

  <!-- ================================================================
         会话回放 Drawer（700px）：sessionApi.detail 渲染 messages 只读流
         ================================================================ -->
  <div
    v-if="replayTarget"
    data-testid="replay-overlay"
    class="fixed inset-0 z-50 bg-slate-900/40"
    @click.self="closeReplay"
    @keydown.esc="closeReplay"
  >
    <aside
      data-testid="session-drawer"
      class="absolute right-0 top-0 flex h-full w-[700px] flex-col border-l border-border bg-surface shadow-md"
      role="dialog"
      aria-modal="true"
    >
      <header class="flex items-center justify-between border-b border-border px-6 py-4">
        <div>
          <h2 class="text-base font-semibold text-text">会话回放</h2>
          <p class="mt-0.5 text-xs text-text-muted">会话 #{{ replayTarget.sessionId }}</p>
        </div>
        <button
          type="button"
          data-testid="close-replay"
          aria-label="关闭回放"
          class="rounded-lg px-2 py-1 text-sm text-text-muted transition-colors duration-150 hover:bg-surface-2"
          @click="closeReplay"
        >
          关闭
        </button>
      </header>
      <div class="flex-1 overflow-y-auto px-6 py-4">
        <!-- 加载中：spinner + 文案 -->
        <div
          v-if="replayLoading"
          class="flex items-center justify-center gap-2 py-10 text-sm text-text-muted"
        >
          <PhSpinnerGap class="h-4 w-4 animate-spin" />
          加载会话消息
        </div>
        <!-- 空消息兜底 -->
        <div
          v-else-if="!replayDetail || replayDetail.messages.length === 0"
          class="py-10 text-center"
        >
          <p class="text-sm text-text-muted">该会话暂无消息记录</p>
        </div>
        <!-- 消息流：role 徽章 + seq 序号 + intentType + content 只读 -->
        <ol v-else class="space-y-3">
          <li
            v-for="msg in replayDetail.messages"
            :key="msg.id"
            class="rounded-lg border border-border bg-surface-2 p-3"
          >
            <div class="flex items-center gap-2 text-xs">
              <Badge :variant="msg.role === 'assistant' ? 'brand' : 'default'">
                {{ msg.role }}
              </Badge>
              <span class="tabular-nums text-text-subtle">seq {{ msg.seq }}</span>
              <span v-if="msg.intentType" class="text-text-subtle">{{ msg.intentType }}</span>
            </div>
            <p class="mt-2 whitespace-pre-wrap break-words text-sm leading-relaxed text-text">
              {{ msg.content }}
            </p>
          </li>
        </ol>
      </div>
    </aside>
  </div>

  <!-- 删除反馈二次确认（危险操作不可恢复，设计 §2.6） -->
  <div
    v-if="deleting"
    data-testid="fb-del-dialog"
    class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4"
    @keydown.esc="cancelDelete"
    @click.self="cancelDelete"
  >
    <div
      class="w-full max-w-[440px] rounded-xl border border-border bg-surface p-6 shadow-md"
      role="alertdialog"
      aria-modal="true"
      @click.stop
    >
      <div class="flex items-start gap-3">
        <div class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-red-50">
          <PhWarningCircle class="h-5 w-5 text-danger" />
        </div>
        <div>
          <h2 class="text-base font-semibold text-text">删除反馈</h2>
          <p class="mt-2 text-sm leading-relaxed text-text-muted">
            删除后该条赞踩记录从报表中移除，
            <span class="font-medium text-danger">此操作不可恢复</span>。确认删除？
          </p>
        </div>
      </div>
      <div class="mt-5 flex justify-end gap-2">
        <Button variant="outline" :disabled="deleteSubmitting" @click="cancelDelete">取消</Button>
        <Button
          variant="danger"
          data-testid="confirm-fb-del"
          :disabled="deleteSubmitting"
          @click="confirmDelete"
        >
          <PhSpinnerGap v-if="deleteSubmitting" class="h-4 w-4 animate-spin" />
          {{ deleteSubmitting ? '删除中' : '确认删除' }}
        </Button>
      </div>
    </div>
  </div>
</template>
