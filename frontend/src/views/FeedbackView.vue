<script setup lang="ts">
/**
 * 反馈报表页（设计 §2.4.6；2026-08-27 紫系换肤重制：
 * PageHead/StatCard/DataTable/EmptyState/ConfirmDialog 新设计系统组件）
 *
 * 能力清单：
 * 1. KPI 4 卡：总反馈（列表计数）/ 点赞 / 点踩 / 点赞率（feedbacks/stats 三字段求和，
 *    非空总和为 0 时点赞率显示占位）。**无环比、无课程维度图**（后端无数据，D11 禁止假数据）。
 * 2. 图 1（全宽）柱状图：近 7 日每日反馈数（dashboardApi.feedbackTrend，无赞踩分列 G7；
 *    TrendBarChart CSS 自绘共享组件，图表库已随 2026-08-27 紫系换肤移除）
 * 3. 图 2 意图×赞踩堆叠条卡：stats 的 intentType/likedCount/dislikedCount 三字段
 *    （IntentLikeBar CSS 横向堆叠条共享组件，点赞 success 绿 / 点踩 danger 红）
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
import { computed, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { PhChatCircleText, PhPercent, PhThumbsDown, PhThumbsUp } from '@phosphor-icons/vue'

import IntentLikeBar from '@/components/charts/IntentLikeBar.vue'
import TrendBarChart from '@/components/charts/TrendBarChart.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { DataTable } from '@/components/ui/data-table'
import { EmptyState } from '@/components/ui/empty-state'
import { PageHead } from '@/components/ui/page-head'
import { StatCard } from '@/components/ui/stat-card'
import { vReveal } from '@/directives/reveal'
import { ApiError, dashboardApi, feedbackApi } from '@/lib/api'
import { showToast } from '@/lib/toast'
import { formatDateTime } from '@/lib/utils'
import { useAuthStore } from '@/stores/auth'
import ConversationReplayDrawer from '@/components/ConversationReplayDrawer.vue'

import type { UserFeedbackVO } from '@/lib/types'

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
// 数据加载（列表 + 统计 + 趋势并行，四态页面级收敛；vue-query 合并单查询）
// ====================================================================

const page = ref(1)
const intentType = ref('')

/** 查询键：意图筛选/页码任一变化即重查（KPI/图表基于全局 stats 不受影响，与原重拉行为一致） */
const queryKey = computed(() => ['admin-feedback', { intentType: intentType.value }, page.value])

const {
  data,
  isLoading,
  isError,
  error: queryError,
  refetch,
} = useQuery({
  queryKey,
  queryFn: async () => {
    // 三接口并行（与原 Promise.all 语义一致）：任一失败整页 error 横幅
    const [res, s, t] = await Promise.all([
      feedbackApi.list({
        page: page.value,
        size: PAGE_SIZE,
        ...(intentType.value ? { intentType: intentType.value } : {}),
      }),
      feedbackApi.stats(),
      dashboardApi.feedbackTrend(7),
    ])
    return { list: res.records ?? [], total: res.total, stats: s ?? [], trend: t ?? [] }
  },
})

/** 列表行数据：total 为 Long 字符串铁律 */
const list = computed(() => data.value?.list ?? [])
const stats = computed(() => data.value?.stats ?? [])
const trend = computed(() => data.value?.trend ?? [])
const total = computed(() => data.value?.total ?? '0')

/** 总页数：total 为 Long 字符串，转 number 后按 PAGE_SIZE 上取整 */
const totalPages = computed(() => Math.max(1, Math.ceil(Number(total.value) / PAGE_SIZE)))

/** 整页加载失败横幅文案（queryError 非空时透出；503 统一降级） */
const listError = computed(() =>
  isError.value ? messageOf(queryError.value, '反馈报表加载失败，请稍后重试') : '',
)

/** 接口错误分级文案（ApiError 透出 message，503 统一降级；非 ApiError 兜底） */
function messageOf(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    return err.code === 503 ? '服务暂时不可用，请稍后重试' : err.message
  }
  return fallback
}

const queryClient = useQueryClient()

/** 删除成功后的刷新：末页最后一条被删会留空页——回退一页（页码变化自动重拉），否则失效列表键 */
function refreshFeedback() {
  if (list.value.length === 1 && page.value > 1) {
    page.value -= 1
  } else {
    queryClient.invalidateQueries({ queryKey: ['admin-feedback'] })
  }
}

/** 意图筛选变更：重置第 1 页（查询键变化自动重查） */
function onFilterChange(e: Event) {
  intentType.value = (e.target as HTMLSelectElement).value
  page.value = 1
}

/** 翻页：越界保护，页码变化自动重拉 */
function changePage(next: number) {
  if (next < 1 || next > totalPages.value) return
  page.value = next
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
// 图 1 柱状图（TrendBarChart）+ 图 2 意图×赞踩堆叠条（IntentLikeBar）
// —— 两图均为 CSS 自绘共享组件，数据在组件内换算（count/liked/disliked 字符串转 number），
//    颜色走 @theme 图表令牌与状态语义色，无 JS 侧取色（图表库已移除）
// ====================================================================

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
// 会话回放 Drawer（公共组件 ConversationReplayDrawer；仅超管入口）
// ====================================================================

const replayOpen = ref(false)
const replaySessionId = ref('')

/** 打开回放 Drawer：记录会话 id 并展开（detail 拉取与 loading 由组件内部承担） */
function openReplay(fb: UserFeedbackVO) {
  replaySessionId.value = fb.sessionId
  replayOpen.value = true
}

/** 关闭回放 Drawer（加载中拦截在组件内部） */
function closeReplay() {
  replayOpen.value = false
}

// ====================================================================
// 删除（两角色均可，二次确认）
// ====================================================================

const deleting = ref<UserFeedbackVO | null>(null)

/** 删除反馈提交（成功后失效列表键，末页空页回退见 refreshFeedback） */
const { isPending: deleteSubmitting, mutate: confirmDeleteMutation } = useMutation({
  mutationFn: (id: string) => feedbackApi.remove(id),
  onSuccess: () => {
    showToast('反馈已删除', 'success')
    deleting.value = null
    refreshFeedback()
  },
  onError: (err) => {
    showToast(messageOf(err, '删除失败，请稍后重试'), 'danger')
  },
})

function requestDelete(fb: UserFeedbackVO) {
  deleting.value = fb
}

function cancelDelete() {
  if (deleteSubmitting.value) return
  deleting.value = null
}

/** 确认删除：提交中禁用按钮，完成/失败由 mutation 回调处理 */
function confirmDelete() {
  if (!deleting.value) return
  confirmDeleteMutation(deleting.value.id)
}
</script>

<template>
  <!-- 页头：主标题 + 副题（回放/删除入口在行内，无页级动作） -->
  <PageHead v-reveal title="反馈报表" subtitle="学生对 AI 回复的赞踩评价与意图分布" />

  <!-- 加载态：骨架屏与最终布局同形（KPI 灰块 + 图表灰块 + 表格灰行） -->
  <div
    v-if="isLoading"
    data-testid="feedback-skeleton"
    class="mt-5 space-y-5"
    aria-label="反馈报表加载中"
  >
    <div class="grid grid-cols-2 gap-[22px] xl:grid-cols-4">
      <div
        v-for="i in 4"
        :key="`kpi-${i}`"
        class="h-[122px] animate-pulse rounded-2xl bg-brand-light"
      />
    </div>
    <div class="h-[330px] animate-pulse rounded-2xl bg-brand-light" />
    <div class="h-[280px] animate-pulse rounded-2xl bg-brand-light" />
    <div class="h-[330px] animate-pulse rounded-2xl bg-brand-light" />
  </div>

  <!-- 错误态：页内横幅 + 重试（设计 §1.7） -->
  <div
    v-else-if="listError"
    role="alert"
    class="mt-5 flex items-center justify-between gap-4 rounded-xl border border-danger/30 bg-danger/5 px-4 py-3"
  >
    <span class="text-sm text-danger">{{ listError }}</span>
    <Button variant="outline" size="sm" data-testid="retry-feedback" @click="refetch">重试</Button>
  </div>

  <!-- 正常态 -->
  <template v-else>
    <!-- KPI 行：4 卡（总反馈/点赞/点踩/点赞率），stat-card 形态（lav 底 + 图标圆 + 大数值） -->
    <div v-reveal="60" class="mt-5 grid grid-cols-2 gap-[22px] xl:grid-cols-4">
      <StatCard
        data-testid="kpi-total"
        class="tabular-nums"
        label="总反馈"
        :value="total"
        tone="brand"
      >
        <template #icon>
          <PhChatCircleText class="h-[21px] w-[21px]" weight="bold" aria-hidden="true" />
        </template>
      </StatCard>
      <StatCard
        data-testid="kpi-liked"
        class="tabular-nums"
        label="点赞"
        :value="likedTotal"
        tone="success"
      >
        <template #icon>
          <PhThumbsUp class="h-[21px] w-[21px]" weight="bold" aria-hidden="true" />
        </template>
      </StatCard>
      <StatCard
        data-testid="kpi-disliked"
        class="tabular-nums"
        label="点踩"
        :value="dislikedTotal"
        tone="danger"
      >
        <template #icon>
          <PhThumbsDown class="h-[21px] w-[21px]" weight="bold" aria-hidden="true" />
        </template>
      </StatCard>
      <StatCard
        data-testid="kpi-rate"
        class="tabular-nums"
        label="点赞率"
        :value="likeRateText"
        tone="brand"
      >
        <template #icon>
          <PhPercent class="h-[21px] w-[21px]" weight="bold" aria-hidden="true" />
        </template>
      </StatCard>
    </div>

    <!-- 图 1（全宽）：柱状图每日反馈数（trend 空时降级区块空态） -->
    <div v-reveal="120" class="mt-6 rounded-2xl border border-border bg-surface p-6 shadow-xs">
      <div class="flex items-center justify-between">
        <h2 class="text-lg font-extrabold tracking-tight text-text">每日反馈数</h2>
        <span class="text-xs font-semibold text-text-subtle">近 7 日</span>
      </div>
      <div v-if="trend.length > 0" class="mt-4 h-56">
        <TrendBarChart :items="trend" />
      </div>
      <div v-else class="flex h-56 items-center justify-center">
        <p class="text-sm text-text-muted">近 7 日暂无反馈记录</p>
      </div>
    </div>

    <!-- 图 2：意图×赞踩堆叠条卡（stats 空时降级区块空态） -->
    <div v-reveal="160" class="mt-6 rounded-2xl border border-border bg-surface p-6 shadow-xs">
      <h2 class="text-lg font-extrabold tracking-tight text-text">意图 × 赞踩分布</h2>
      <div v-if="stats.length > 0" class="mt-4 h-48">
        <IntentLikeBar :stats="stats" />
      </div>
      <div v-else class="flex h-48 items-center justify-center">
        <p class="text-sm text-text-muted">暂无意图统计</p>
      </div>
    </div>

    <!-- 筛选工具条：意图筛选（native select 保持 setValue/change 契约） -->
    <div v-reveal="200" class="mt-6 flex flex-wrap items-center gap-2">
      <select
        data-testid="filter-intent"
        aria-label="按意图筛选"
        :value="intentType"
        class="h-10 rounded-xl border border-border bg-surface px-3 text-sm font-semibold text-text outline-none transition-colors duration-150 focus:border-brand focus:ring-2 focus:ring-brand/20"
        @change="onFilterChange"
      >
        <option value="">全部意图</option>
        <option v-for="opt in INTENT_OPTIONS" :key="opt.value" :value="opt.value">
          {{ opt.label }}
        </option>
      </select>
    </div>

    <!-- 空态：语义文案（筛选后无记录） -->
    <div
      v-if="list.length === 0"
      v-reveal
      class="mt-4 rounded-2xl border border-border bg-surface shadow-xs"
    >
      <EmptyState title="还没有反馈记录" description="学生对话后对 AI 回复进行赞踩评价后汇聚于此">
        <template #icon>
          <PhChatCircleText class="h-6 w-6" aria-hidden="true" />
        </template>
      </EmptyState>
    </div>

    <template v-else>
      <div
        v-reveal
        class="mt-4 overflow-hidden rounded-2xl border border-border bg-surface shadow-xs"
      >
        <DataTable data-testid="fb-table" label="反馈列表">
          <template #header>
            <tr>
              <th class="w-24">#id</th>
              <th class="w-36">用户</th>
              <th class="w-44">意图</th>
              <th class="w-24">评价</th>
              <th class="w-36">时间</th>
              <th class="w-64">操作</th>
            </tr>
          </template>
          <tr v-for="fb in list" :key="fb.id" :data-testid="`row-${fb.id}`">
            <td>
              <span class="font-semibold text-text">#{{ fb.id }}</span>
            </td>
            <td :data-testid="`fb-user-${fb.id}`" class="tabular-nums">
              {{ shortId(fb.userId) }}
            </td>
            <td>
              <Badge :data-testid="`fb-intent-${fb.id}`" :variant="intentVariant(fb.intentType)">
                {{ fb.intentType ?? '未标注' }}
              </Badge>
            </td>
            <td>
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
            <td :data-testid="`fb-time-${fb.id}`" class="tabular-nums">
              {{ formatDateTime(fb.createdAt) }}
            </td>
            <td>
              <div class="flex items-center gap-1">
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
        </DataTable>
      </div>

      <!-- 分页器：左「共 N 条」右 上/下页 + 页码（设计 §2.6） -->
      <div class="mt-4 flex flex-wrap items-center justify-between gap-2 text-sm text-text-muted">
        <span>
          共 <span class="tabular-nums font-semibold text-text">{{ total }}</span> 条
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

  <!-- 会话回放 Drawer（公共组件：detail 拉取 + messages 只读流；仅超管入口展示） -->
  <ConversationReplayDrawer :open="replayOpen" :session-id="replaySessionId" @close="closeReplay" />

  <!-- 删除反馈二次确认（危险操作不可恢复，设计 §2.6；
       ConfirmDialog 标准壳：$attrs 转发保住 confirm-fb-del 选择器契约，外层 v-if 包装 div 承载 fb-del-dialog） -->
  <div v-if="deleting" data-testid="fb-del-dialog" class="contents">
    <ConfirmDialog
      :open="true"
      title="删除反馈"
      description="删除后该条赞踩记录从报表中移除，此操作不可恢复。确认删除？"
      confirm-text="确认删除"
      tone="danger"
      :loading="deleteSubmitting"
      data-testid="confirm-fb-del"
      @confirm="confirmDelete"
      @cancel="cancelDelete"
    />
  </div>
</template>
