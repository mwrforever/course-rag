<script setup lang="ts">
/**
 * 会话回放抽屉（公共组件，FeedbackView 与 SessionsAdminView 共用）
 *
 * 700px 只读消息抽屉：打开时调 sessionApi.detail 拉取会话完整消息流
 * （role 徽章 + seq 序号 + intentType + content），消息仅展示不编辑。
 *
 * 权限差异（回放入口仅超管可见）保留在调用方，本组件不做权限判断；
 * 状态徽章语义 ACTIVE→success / 其余→default，与 SessionsAdminView 原实现一致。
 */
import { computed, ref, watch } from 'vue'

import { PhSpinnerGap } from '@phosphor-icons/vue'

import { Badge } from '@/components/ui/badge'
import { ApiError, sessionApi } from '@/lib/api'
import { showToast } from '@/lib/toast'
import type { ChatSessionDetailVO, SessionStatus } from '@/lib/types'

const props = withDefaults(
  defineProps<{
    /** 抽屉显隐：由调用方（回放入口按钮）控制，打开时自动拉取会话明细 */
    open: boolean
    /** 会话 id：detail 拉取与「会话 #id」头部展示 */
    sessionId: string
    /** 头部主标题（Sessions 传入会话标题；Feedback 缺省「会话回放」） */
    title?: string
    /** 列表行初始状态：详情加载完成前展示，加载后以明细 status 为准 */
    initialStatus?: SessionStatus | ''
  }>(),
  { title: '会话回放', initialStatus: '' },
)

const emit = defineEmits<{ close: [] }>()

const detail = ref<ChatSessionDetailVO | null>(null)
const loading = ref(false)

/** 展示状态：详情加载后以明细为准（列表行可能滞后，如刚被外部关闭） */
const status = computed<SessionStatus>(() => detail.value?.status ?? props.initialStatus)

/** 会话状态 → Badge 语义变体（ACTIVE 绿 / CLOSED 中性） */
function statusVariant(s: SessionStatus) {
  return s === 'ACTIVE' ? ('success' as const) : ('default' as const)
}

/** 接口错误分级文案（ApiError 透出 message，503 统一降级；非 ApiError 兜底） */
function messageOf(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    return err.code === 503 ? '服务暂时不可用，请稍后重试' : err.message
  }
  return fallback
}

/** 关闭抽屉：加载中拦截（防丢加载态） */
function close() {
  if (loading.value) return
  emit('close')
}

watch(
  () => props.open,
  (open) => {
    // 每次打开重置明细并拉取最新消息；失败关抽屉 + toast（与两视图原行为一致）；
    // immediate：初始 open=true 挂载（如父组件条件渲染）同样拉取
    if (!open) return
    detail.value = null
    loading.value = true
    sessionApi
      .detail(props.sessionId)
      .then((res) => {
        detail.value = res
      })
      .catch((err) => {
        showToast(messageOf(err, '会话详情加载失败，请稍后重试'), 'danger')
        emit('close')
      })
      .finally(() => {
        loading.value = false
      })
  },
  { immediate: true },
)
</script>

<template>
  <!-- 会话回放 Drawer（700px）：detail 渲染 messages 只读流 -->
  <div
    v-if="open"
    data-testid="replay-overlay"
    class="fixed inset-0 z-50 bg-slate-900/40"
    @click.self="close"
    @keydown.esc="close"
  >
    <aside
      data-testid="session-drawer"
      class="absolute right-0 top-0 flex h-full w-[700px] flex-col border-l border-border bg-surface shadow-md"
      role="dialog"
      aria-modal="true"
    >
      <header class="flex items-center justify-between border-b border-border px-6 py-4">
        <div>
          <h2 class="text-base font-semibold text-text">{{ title }}</h2>
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
          class="rounded-lg px-2 py-1 text-sm text-text-muted transition-colors duration-150 hover:bg-surface-2"
          @click="close"
        >
          关闭
        </button>
      </header>
      <div class="flex-1 overflow-y-auto px-6 py-4">
        <!-- 加载中：spinner + 文案 -->
        <div
          v-if="loading"
          class="flex items-center justify-center gap-2 py-10 text-sm text-text-muted"
        >
          <PhSpinnerGap class="h-4 w-4 animate-spin" />
          加载会话消息
        </div>
        <!-- 空消息兜底 -->
        <div v-else-if="!detail || detail.messages.length === 0" class="py-10 text-center">
          <p class="text-sm text-text-muted">该会话暂无消息记录</p>
        </div>
        <!-- 消息流：role 徽章 + seq 序号 + intentType + content 只读 -->
        <ol v-else class="space-y-3">
          <li
            v-for="msg in detail.messages"
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
</template>
