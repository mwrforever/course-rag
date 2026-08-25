<script setup lang="ts">
/**
 * 会话审计页（设计 §2.4.7 会话列表，超管专属：路由 meta.roles 仅 SUPER_ADMIN）
 *
 * 能力清单：
 * 1. 列表：#id / 用户 / 标题 / model / 状态 Badge（ACTIVE emerald / CLOSED slate）/
 *    最后消息时间 / 创建时间
 * 2. 详情 Drawer 700px：sessionApi.detail 回放完整 messages 只读流
 *    （role/content/intentType/seq，逐条消息卡片，服务端时序）
 * 3. 关闭会话：仅 ACTIVE 行入口 → patch close → toast → 刷新
 * 4. 删除会话：二次确认（danger，级联软删消息与 Run）
 * 5. 四态：loading 骨架 / empty / error 横幅重试 / 正常 + 分页
 *
 * 契约要点：id/total 为 Long 字符串铁律；seq 为 Integer 保持 number；
 * 时间 ISO-8601 短格式；仅超管可进（路由层已拦截，页面不做二次角色判断）。
 *
 * 线程安全注意：全部状态为组件私有 ref，无跨实例共享可变状态。
 */
import { computed, onMounted, ref } from 'vue'
import { PhSpinnerGap, PhWarningCircle } from '@phosphor-icons/vue'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ApiError, sessionApi } from '@/lib/api'
import { showToast } from '@/lib/toast'
import { formatDateTime } from '@/lib/utils'

import type { ChatSessionDetailVO, ChatSessionVO, SessionStatus } from '@/lib/types'

/** 每页条数（设计 §2.6 分页器） */
const PAGE_SIZE = 10

// ====================================================================
// 列表数据（分页 + 四态页面级收敛）
// ====================================================================

const sessions = ref<ChatSessionVO[]>([])
const loading = ref(true)
const error = ref('')
const page = ref(1)
const total = ref('0')

/** 总页数：total 为 Long 字符串，转 number 后按 PAGE_SIZE 上取整 */
const totalPages = computed(() => Math.max(1, Math.ceil(Number(total.value) / PAGE_SIZE)))

/** 接口错误分级文案（ApiError 透出 message，503 统一降级；非 ApiError 兜底） */
function messageOf(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    return err.code === 503 ? '服务暂时不可用，请稍后重试' : err.message
  }
  return fallback
}

/** 拉取当前页会话列表（分页参数 page/size） */
async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await sessionApi.list({ page: page.value, size: PAGE_SIZE })
    sessions.value = res.records ?? []
    total.value = res.total
    if (sessions.value.length === 0 && page.value > 1) {
      page.value -= 1
      await load()
    }
  } catch (err) {
    error.value = messageOf(err, '会话列表加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

onMounted(load)

/** 翻页：越界保护 */
function changePage(next: number) {
  if (next < 1 || next > totalPages.value) return
  page.value = next
  load()
}

/** 状态 Badge：ACTIVE emerald / CLOSED slate（设计 §2.5） */
function statusVariant(status: SessionStatus) {
  return status === 'ACTIVE' ? ('success' as const) : ('default' as const)
}

// ====================================================================
// 详情回放 Drawer（700px，messages 只读流）
// ====================================================================

const detailTarget = ref<ChatSessionVO | null>(null)
const replayDetail = ref<ChatSessionDetailVO | null>(null)
const replayLoading = ref(false)

/** 打开详情 Drawer：sessionApi.detail 拉取会话完整消息（只读回放） */
async function openDetail(s: ChatSessionVO) {
  detailTarget.value = s
  replayDetail.value = null
  replayLoading.value = true
  try {
    replayDetail.value = await sessionApi.detail(s.id)
  } catch (err) {
    showToast(messageOf(err, '会话详情加载失败，请稍后重试'), 'danger')
    detailTarget.value = null
  } finally {
    replayLoading.value = false
  }
}

/** 关闭 Drawer：加载中拦截（防丢加载态） */
function closeDetail() {
  if (replayLoading.value) return
  detailTarget.value = null
  replayDetail.value = null
}

// ====================================================================
// 关闭会话（仅 ACTIVE 行入口）
// ====================================================================

const closing = ref<ChatSessionVO | null>(null)
const closingLoading = ref(false)

/** 关闭会话：close → toast → 刷新（行状态随之变 CLOSED） */
async function closeSession(s: ChatSessionVO) {
  closing.value = s
  closingLoading.value = true
  try {
    await sessionApi.close(s.id)
    showToast('会话已关闭', 'success')
    await load()
  } catch (err) {
    showToast(messageOf(err, '关闭失败，请稍后重试'), 'danger')
  } finally {
    closing.value = null
    closingLoading.value = false
  }
}

// ====================================================================
// 删除会话（二次确认，级联软删消息 + Run）
// ====================================================================

const deleting = ref<ChatSessionVO | null>(null)
const deleteSubmitting = ref(false)

function requestDelete(s: ChatSessionVO) {
  deleting.value = s
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
    await sessionApi.remove(deleting.value.id)
    showToast('会话已删除', 'success')
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
  <!-- 错误态：页内横幅 + 重试（设计 §1.7） -->
  <div
    v-if="error"
    role="alert"
    class="flex items-center justify-between gap-4 rounded-lg border border-danger/30 bg-red-50 px-4 py-3"
  >
    <span class="text-sm text-danger">{{ error }}</span>
    <Button variant="outline" size="sm" data-testid="retry-sessions" @click="load">重试</Button>
  </div>

  <!-- 加载态：表格骨架屏（表头 + 5 行灰条，与最终表格同形） -->
  <div
    v-else-if="loading"
    data-testid="session-skeleton"
    class="overflow-hidden rounded-xl border border-border bg-surface"
    aria-label="会话列表加载中"
  >
    <div class="flex items-center gap-6 border-b border-border bg-surface-2 px-4 py-2.5">
      <div v-for="i in 7" :key="`head-${i}`" class="h-3 w-20 animate-pulse rounded bg-slate-200" />
    </div>
    <div
      v-for="i in 5"
      :key="`row-${i}`"
      class="h-11 animate-pulse border-b border-border bg-slate-50"
    />
  </div>

  <!-- 空态：无会话文案（禁裸「暂无数据」） -->
  <div
    v-else-if="sessions.length === 0"
    class="flex flex-col items-center justify-center rounded-xl border border-dashed border-border bg-surface py-14 text-center"
  >
    <PhWarningCircle class="h-8 w-8 text-text-subtle" />
    <p class="mt-3 text-sm font-medium text-text">还没有会话</p>
    <p class="mt-1 text-xs text-text-muted">学生发起的对话会话将在此审计维度汇聚</p>
  </div>

  <!-- 正常态：分页表格（#id/用户/标题/model/状态/最后消息/创建时间/操作） -->
  <template v-else>
    <div class="overflow-hidden rounded-xl border border-border bg-surface">
      <table data-testid="session-table" class="w-full text-sm">
        <thead class="border-b border-border bg-surface-2 text-left text-xs text-text-muted">
          <tr>
            <th class="w-20 px-4 py-2.5 font-medium">#id</th>
            <th class="w-28 px-4 py-2.5 font-medium">用户</th>
            <th class="px-4 py-2.5 font-medium">标题</th>
            <th class="w-28 px-4 py-2.5 font-medium">model</th>
            <th class="w-24 px-4 py-2.5 font-medium">状态</th>
            <th class="w-32 px-4 py-2.5 font-medium">最后消息</th>
            <th class="w-32 px-4 py-2.5 font-medium">创建时间</th>
            <th class="w-44 px-4 py-2.5 text-right font-medium">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="s in sessions"
            :key="s.id"
            :data-testid="`row-${s.id}`"
            class="h-11 border-b border-border last:border-b-0 transition-colors duration-150 hover:bg-surface-2"
          >
            <td class="px-4 tabular-nums text-text-muted">#{{ s.id }}</td>
            <td class="px-4 tabular-nums text-text-muted">{{ s.userId }}</td>
            <td class="max-w-[240px] px-4">
              <p class="truncate font-medium text-text" :title="s.title">{{ s.title }}</p>
            </td>
            <td class="px-4 text-text-muted">{{ s.model || '未指定' }}</td>
            <td class="px-4">
              <Badge :data-testid="`session-status-${s.id}`" :variant="statusVariant(s.status)">
                {{ s.status }}
              </Badge>
            </td>
            <td class="px-4 tabular-nums text-text-muted">
              {{ formatDateTime(s.lastMessageAt) }}
            </td>
            <td class="px-4 tabular-nums text-text-muted">{{ formatDateTime(s.createdAt) }}</td>
            <td class="px-4 text-right">
              <div class="flex items-center justify-end gap-1">
                <Button
                  variant="ghost"
                  size="sm"
                  :data-testid="`op-detail-${s.id}`"
                  @click="openDetail(s)"
                >
                  详情
                </Button>
                <!-- 关闭入口：仅 ACTIVE 行（CLOSED 重复关闭后端会 409） -->
                <Button
                  v-if="s.status === 'ACTIVE'"
                  variant="outline"
                  size="sm"
                  :data-testid="`op-close-${s.id}`"
                  :disabled="closing?.id === s.id"
                  @click="closeSession(s)"
                >
                  <PhSpinnerGap v-if="closing?.id === s.id" class="h-3 w-3 animate-spin" />
                  关闭
                </Button>
                <Button
                  variant="danger"
                  size="sm"
                  :data-testid="`op-delete-${s.id}`"
                  @click="requestDelete(s)"
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

  <!-- ================================================================
         会话详情回放 Drawer（700px）：messages 只读流（role/content/intentType/seq）
         ================================================================ -->
  <div
    v-if="detailTarget"
    data-testid="replay-overlay"
    class="fixed inset-0 z-50 bg-slate-900/40"
    @click.self="closeDetail"
    @keydown.esc="closeDetail"
  >
    <aside
      data-testid="session-drawer"
      class="absolute right-0 top-0 flex h-full w-[700px] flex-col border-l border-border bg-surface shadow-md"
      role="dialog"
      aria-modal="true"
    >
      <header class="flex items-center justify-between border-b border-border px-6 py-4">
        <div>
          <h2 class="text-base font-semibold text-text">{{ detailTarget.title }}</h2>
          <p class="mt-0.5 flex items-center gap-2 text-xs text-text-muted">
            会话 #{{ detailTarget.id }}
            <!-- 状态徽章：详情加载后以明细为准（列表行可能滞后，如刚被外部关闭） -->
            <Badge :variant="statusVariant(replayDetail?.status ?? detailTarget.status)">
              {{ replayDetail?.status ?? detailTarget.status }}
            </Badge>
          </p>
        </div>
        <button
          type="button"
          data-testid="close-replay"
          aria-label="关闭回放"
          class="rounded-lg px-2 py-1 text-sm text-text-muted transition-colors duration-150 hover:bg-surface-2"
          @click="closeDetail"
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

  <!-- 删除会话二次确认（级联软删消息 + Run，不可恢复） -->
  <div
    v-if="deleting"
    data-testid="session-del-dialog"
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
          <h2 class="text-base font-semibold text-text">删除会话</h2>
          <p class="mt-2 text-sm leading-relaxed text-text-muted">
            删除后该会话及其全部消息一并移除，
            <span class="font-medium text-danger">此操作不可恢复</span>。确认删除「{{
              deleting.title
            }}」？
          </p>
        </div>
      </div>
      <div class="mt-5 flex justify-end gap-2">
        <Button variant="outline" :disabled="deleteSubmitting" @click="cancelDelete">取消</Button>
        <Button
          variant="danger"
          data-testid="confirm-session-del"
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
