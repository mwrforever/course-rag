<script setup lang="ts">
/**
 * 会话回放抽屉（公共组件，FeedbackView 与 SessionsAdminView 共用）
 *
 * 700px 只读消息抽屉：打开时经 useQuery（['session-detail', id]，enabled=open）拉取
 * 会话完整消息流（role 徽章 + seq 序号 + intentType + content），消息仅展示不编辑；
 * 30s staleTime 窗口内重开同一会话命中缓存 0 请求（PERF-08，props/emit 契约不变）。
 * 视觉形态（2026-08-27 紫系换肤 N8b）：紫黑遮罩 + 2px 毛玻璃 + 面板右滑入
 * （设计稿 backdrop / A26 参数）+ 消息气泡列表（角色头像圆 + 差异化气泡底色）。
 *
 * 权限差异（回放入口仅超管可见）保留在调用方，本组件不做权限判断；
 * 状态徽章语义 ACTIVE→success / 其余→default，与 SessionsAdminView 原实现一致。
 *
 * props/emit 契约冻结（N8a FeedbackView 依赖）：open/sessionId/title/initialStatus + close。
 */
import { computed, watch } from 'vue'

import { useQuery } from '@tanstack/vue-query'
import { PhChatCircleDots, PhRobot, PhSpinnerGap, PhUser, PhX } from '@phosphor-icons/vue'

import { Badge } from '@/components/ui/badge'
import { EmptyState } from '@/components/ui/empty-state'
import { ApiError, sessionApi } from '@/lib/api'
import { showToast } from '@/lib/toast'
import type { SessionStatus } from '@/lib/types'

const props = withDefaults(
  defineProps<{
    /** 抽屉显隐：由调用方（回放入口按钮）控制，作为查询 enabled 开关（打开才拉取/命中缓存） */
    open: boolean
    /** 会话 id：detail 拉取与「会话 #id」头部展示，作为查询键维度 */
    sessionId: string
    /** 头部主标题（Sessions 传入会话标题；Feedback 缺省「会话回放」） */
    title?: string
    /** 列表行初始状态：详情加载完成前展示，加载后以明细 status 为准 */
    initialStatus?: SessionStatus | ''
  }>(),
  { title: '会话回放', initialStatus: '' },
)

const emit = defineEmits<{ close: [] }>()

// 会话明细查询：键=会话 id 维度、enabled=抽屉打开；staleTime 继承全局 30s——
// 关闭不清缓存（query 卸载仅断观察，缓存留 QueryClient），30s 内重开同会话 0 请求；
// retry 关闭保持原手动 fetch「单次失败立即 toast+收合」契约，避免重试期间抽屉悬停
const {
  data: detail,
  isPending: loading,
  error,
} = useQuery({
  queryKey: computed(() => ['session-detail', props.sessionId]),
  queryFn: () => sessionApi.detail(props.sessionId),
  enabled: computed(() => props.open),
  retry: false,
})

/** 展示状态：详情加载后以明细为准（列表行可能滞后，如刚被外部关闭）；initialStatus 允许 '' 占位 */
const status = computed<SessionStatus | ''>(() => detail.value?.status ?? props.initialStatus)

/** 会话状态 → Badge 语义变体（ACTIVE 绿 / CLOSED 中性；'' 占位归中性） */
function statusVariant(s: SessionStatus | '') {
  return s === 'ACTIVE' ? ('success' as const) : ('default' as const)
}

/** 接口错误分级文案（ApiError 透出 message，503 统一降级；非 ApiError 兜底） */
function messageOf(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    return err.code === 503 ? '服务暂时不可用，请稍后重试' : err.message
  }
  return fallback
}

/** 关闭抽屉：加载中拦截（防丢加载态）；命中缓存秒开时 data 已在场不拦截 */
function close() {
  if (loading.value) return
  emit('close')
}

// 拉取失败：danger toast + 通知父组件收合（与原手动 fetch 的 catch 行为一致；
// 错误不缓存为数据，重开同一会话会重新拉取）
watch(error, (err) => {
  if (!err) return
  showToast(messageOf(err, '会话详情加载失败，请稍后重试'), 'danger')
  emit('close')
})
</script>

<template>
  <!-- 会话回放 Drawer（700px）：遮罩紫黑半透明 + 轻毛玻璃（设计稿 backdrop 形态） -->
  <div
    v-if="open"
    data-testid="replay-overlay"
    class="fixed inset-0 z-50 animate-fade-in bg-overlay backdrop-blur-[2px]"
    @click.self="close"
    @keydown.esc="close"
  >
    <!-- 抽屉面板：右缘贴齐 + 700px 固定宽（窄屏兜底收缩）+ 滑入动画见 scoped -->
    <aside
      data-testid="session-drawer"
      class="drawer-panel absolute top-0 right-0 flex h-full w-[700px] max-w-[92vw] flex-col border-l border-border bg-surface shadow-lg"
      role="dialog"
      aria-modal="true"
    >
      <!-- 头部：标题 + 会话标识与状态徽章 + 图标关闭钮 -->
      <header class="flex shrink-0 items-center justify-between border-b border-border px-6 py-4">
        <div class="min-w-0">
          <h2 class="truncate text-base font-semibold text-text" :title="title">{{ title }}</h2>
          <p class="mt-0.5 flex items-center gap-2 text-xs text-text-muted">
            会话 #{{ sessionId }}
            <!-- 状态徽章：详情加载后以明细为准（列表行可能滞后，如刚被外部关闭） -->
            <Badge v-if="initialStatus" :variant="statusVariant(status)">
              {{ status }}
            </Badge>
          </p>
        </div>
        <button
          type="button"
          data-testid="close-replay"
          aria-label="关闭回放"
          class="grid h-9 w-9 shrink-0 place-items-center rounded-lg text-text-muted transition-colors duration-150 hover:bg-surface-2 hover:text-text"
          @click="close"
        >
          <PhX class="h-4.5 w-4.5" />
        </button>
      </header>
      <div class="flex-1 overflow-y-auto bg-bg/40 px-6 py-5">
        <!-- 加载中：spinner + 文案 -->
        <div
          v-if="loading"
          class="flex items-center justify-center gap-2 py-10 text-sm text-text-muted"
        >
          <PhSpinnerGap class="h-4 w-4 animate-spin" />
          加载会话消息
        </div>
        <!-- 空消息兜底：统一空态形态（图标圆 + 标题） -->
        <EmptyState
          v-else-if="!detail || detail.messages.length === 0"
          title="该会话暂无消息记录"
          description="消息只在会话产生过对话后才会留存"
        >
          <template #icon>
            <PhChatCircleDots class="h-6 w-6" aria-hidden="true" />
          </template>
        </EmptyState>
        <!-- 消息流气泡列表：角色头像圆 + role 徽章 + seq/intentType 元信息 + content 只读 -->
        <ol v-else class="space-y-4">
          <li v-for="msg in detail.messages" :key="msg.id" class="flex items-start gap-3">
            <!-- 角色头像圆：assistant 紫系 / user 中性（气泡列表的视觉锚点） -->
            <span
              class="mt-0.5 grid h-8 w-8 shrink-0 place-items-center rounded-full"
              :class="
                msg.role === 'assistant'
                  ? 'bg-brand-soft text-brand'
                  : 'bg-surface-2 text-text-muted'
              "
            >
              <PhRobot v-if="msg.role === 'assistant'" class="h-4.5 w-4.5" />
              <PhUser v-else class="h-4.5 w-4.5" />
            </span>
            <!-- 气泡卡：assistant 浅紫底 / user 次表面底，圆角 16px -->
            <div
              class="min-w-0 flex-1 rounded-2xl px-4 py-3"
              :class="msg.role === 'assistant' ? 'bg-brand-soft/60' : 'bg-surface-2'"
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
            </div>
          </li>
        </ol>
      </div>
    </aside>
  </div>
</template>

<style scoped>
/* 抽屉滑入：右缘外 100% 平移至复位（设计稿 A26 transform .45s ease 参数）；
   reduced-motion 由 main.css 全局降级总开关接管（animation: none） */
.drawer-panel {
  animation: drawer-in 0.45s var(--ease) both;
}

@keyframes drawer-in {
  from {
    transform: translateX(100%);
  }
}
</style>
