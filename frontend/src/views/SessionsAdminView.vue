<script setup lang="ts">
/**
 * 会话审计页（设计 §2.4.7 会话列表，超管专属：路由 meta.roles 仅 SUPER_ADMIN）
 *
 * 能力清单：
 * 1. 列表：#id / 用户 / 标题 / model / 状态 Badge（ACTIVE emerald / CLOSED slate）/
 *    最后消息时间 / 创建时间
 * 2. 详情 Drawer 700px：sessionApi.detail 回放完整 messages 只读流
 *    （role/content/intentType/seq，逐条消息气泡，服务端时序）
 * 3. 关闭会话：仅 ACTIVE 行入口 → 二次确认（ConfirmDialog）→ patch close →
 *    toast → 刷新（BUG-31：终止学生活跃对话属高影响操作，补确认防误触）
 * 4. 删除会话：二次确认（ConfirmDialog danger，级联软删消息与 Run）
 * 5. 四态：loading 骨架 / empty / error 横幅重试 / 正常 + 分页
 *
 * 视觉形态（2026-08-27 紫系换肤 N8b）：PageHead 页头 + 卡片化 DataTable
 * （lav 表头 / 行悬停 / 行级联入场，N2 组件）+ EmptyState 统一空态 + ConfirmDialog。
 *
 * 契约要点：id/total 为 Long 字符串铁律；seq 为 Integer 保持 number；
 * 时间 ISO-8601 短格式；仅超管可进（路由层已拦截，页面不做二次角色判断）。
 *
 * 线程安全注意：全部状态为组件私有 ref，无跨实例共享可变状态。
 */
import { computed, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { PhChatCircleDots, PhSpinnerGap } from '@phosphor-icons/vue'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { DataTable } from '@/components/ui/data-table'
import { EmptyState } from '@/components/ui/empty-state'
import { PageHead } from '@/components/ui/page-head'
import { ApiError, sessionApi } from '@/lib/api'
import { showToast } from '@/lib/toast'
import { formatDateTime } from '@/lib/utils'
import ConversationReplayDrawer from '@/components/ConversationReplayDrawer.vue'

import type { ChatSessionVO, SessionStatus } from '@/lib/types'

/** 每页条数（设计 §2.6 分页器） */
const PAGE_SIZE = 10

// ====================================================================
// 列表数据（分页 + 四态页面级收敛）
// ====================================================================

/** 页码：查询键组成之一，变化自动触发新查询 */
const page = ref(1)

/** 查询键：页码变化即重拉当前页（vue-query 数据源，C.1.4） */
const queryKey = computed(() => ['admin-sessions', page.value])

const {
  data,
  isLoading,
  isError,
  error: queryError,
  refetch,
} = useQuery({
  queryKey,
  queryFn: () => sessionApi.list({ page: page.value, size: PAGE_SIZE }),
})

/** 列表行数据：total 为 Long 字符串铁律 */
const sessions = computed(() => data.value?.records ?? [])
const total = computed(() => data.value?.total ?? '0')

/** 总页数：total 为 Long 字符串，转 number 后按 PAGE_SIZE 上取整 */
const totalPages = computed(() => Math.max(1, Math.ceil(Number(total.value) / PAGE_SIZE)))

/** 列表加载失败横幅文案（queryError 非空时透出；503 统一降级） */
const listError = computed(() =>
  isError.value ? messageOf(queryError.value, '会话列表加载失败，请稍后重试') : '',
)

/** 接口错误分级文案（ApiError 透出 message，503 统一降级；非 ApiError 兜底） */
function messageOf(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    return err.code === 503 ? '服务暂时不可用，请稍后重试' : err.message
  }
  return fallback
}

const queryClient = useQueryClient()

/** 写操作成功后的列表刷新：不减少行数的操作直接按查询键失效重拉（删除类见删除 mutation 内联回退） */
function refreshSessions() {
  queryClient.invalidateQueries({ queryKey: ['admin-sessions'] })
}

/** 翻页：越界保护，页码变化自动重拉 */
function changePage(next: number) {
  if (next < 1 || next > totalPages.value) return
  page.value = next
}

/** 状态 Badge：ACTIVE emerald / CLOSED slate（设计 §2.5） */
function statusVariant(status: SessionStatus) {
  return status === 'ACTIVE' ? ('success' as const) : ('default' as const)
}

// ====================================================================
// 详情回放 Drawer（公共组件 ConversationReplayDrawer；仅超管入口）
// ====================================================================

const detailOpen = ref(false)
const detailSession = ref<ChatSessionVO | null>(null)

/** 打开详情 Drawer：记录会话并展开（detail 拉取与 loading 由组件内部承担） */
function openDetail(s: ChatSessionVO) {
  detailSession.value = s
  detailOpen.value = true
}

/** 关闭 Drawer（加载中拦截在组件内部） */
function closeDetail() {
  detailOpen.value = false
}

// ====================================================================
// 关闭会话（仅 ACTIVE 行入口；二次确认，与删除会话同款 ConfirmDialog）
// ====================================================================

/** 确认弹窗目标会话（null = 关闭弹窗；提交中行内 spinner 由 isPending 驱动） */
const closing = ref<ChatSessionVO | null>(null)

/** 关闭会话提交（行状态随之变 CLOSED；成功后失效列表键） */
const { isPending: closeSubmitting, mutate: closeSessionMutation } = useMutation({
  mutationFn: (id: string) => sessionApi.close(id),
  onSuccess: () => {
    showToast('会话已关闭', 'success')
    closing.value = null
    refreshSessions()
  },
  onError: (err) => {
    closing.value = null
    showToast(messageOf(err, '关闭失败，请稍后重试'), 'danger')
  },
})

/** 请求关闭：仅记录目标并弹二次确认（确认前不调接口，防误触终止学生活跃对话） */
function requestClose(s: ChatSessionVO) {
  closing.value = s
}

/** 取消关闭：清空确认目标（提交中拦截，收口由 mutation 回调负责） */
function cancelClose() {
  if (closeSubmitting.value) return
  closing.value = null
}

/** 确认关闭：提交 mutation，完成/失败由回调处理 */
function confirmClose() {
  if (!closing.value) return
  closeSessionMutation(closing.value.id)
}

// ====================================================================
// 删除会话（二次确认，级联软删消息 + Run）
// ====================================================================

const deleting = ref<ChatSessionVO | null>(null)

/** 删除会话提交（级联软删消息 + Run；成功后失效列表键，末页空页回退见 refreshSessions） */
const { isPending: deleteSubmitting, mutate: confirmDeleteMutation } = useMutation({
  mutationFn: (id: string) => sessionApi.remove(id),
  onSuccess: () => {
    showToast('会话已删除', 'success')
    deleting.value = null
    if (sessions.value.length === 1 && page.value > 1) {
      page.value -= 1
    } else {
      queryClient.invalidateQueries({ queryKey: ['admin-sessions'] })
    }
  },
  onError: (err) => {
    showToast(messageOf(err, '删除失败，请稍后重试'), 'danger')
  },
})

function requestDelete(s: ChatSessionVO) {
  deleting.value = s
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
  <div class="flex flex-col gap-5">
    <!-- 页头：标题 + 副题（滚动入场，设计稿 page-head 形态） -->
    <PageHead
      v-reveal
      title="会话审计"
      subtitle="学生发起的 AI 对话会话在此只读汇聚，支持逐条回放与治理"
    />

    <!-- 错误态：页内横幅 + 重试（设计 §1.7） -->
    <div
      v-if="listError"
      role="alert"
      class="flex items-center justify-between gap-4 rounded-xl border border-danger/30 bg-red-50 px-4 py-3"
    >
      <span class="text-sm text-danger">{{ listError }}</span>
      <Button variant="outline" size="sm" data-testid="retry-sessions" @click="refetch">
        重试
      </Button>
    </div>

    <!-- 加载态：表格骨架屏（表头 + 5 行灰条，与最终表格同形） -->
    <div
      v-else-if="isLoading"
      data-testid="session-skeleton"
      class="overflow-hidden rounded-2xl border border-border bg-surface shadow-xs"
      aria-label="会话列表加载中"
    >
      <div class="flex items-center gap-6 border-b border-border bg-surface-2 px-5 py-3.5">
        <div
          v-for="i in 7"
          :key="`head-${i}`"
          class="h-3 w-20 animate-pulse rounded bg-slate-200"
        />
      </div>
      <div
        v-for="i in 5"
        :key="`row-${i}`"
        class="h-11 animate-pulse border-b border-border last:border-b-0 bg-slate-50"
      />
    </div>

    <!-- 空态：统一空态形态（图标圆 + 标题 + 描述） -->
    <div
      v-else-if="sessions.length === 0"
      class="rounded-2xl border border-border bg-surface shadow-xs"
    >
      <EmptyState title="还没有会话" description="学生发起的对话会话将在此审计维度汇聚">
        <template #icon>
          <PhChatCircleDots class="h-6 w-6" aria-hidden="true" />
        </template>
      </EmptyState>
    </div>

    <!-- 正常态：分页表格（#id/用户/标题/model/状态/最后消息/创建时间/操作） -->
    <template v-else>
      <div
        v-reveal="80"
        class="overflow-hidden rounded-2xl border border-border bg-surface shadow-xs"
      >
        <!-- DataTable 承担表格视觉壳（lav 圆角表头/行悬停/行级联入场），单元格由插槽提供 -->
        <DataTable data-testid="session-table" label="会话审计列表">
          <template #header>
            <tr>
              <th class="w-20">#id</th>
              <th class="w-28">用户</th>
              <th>标题</th>
              <th class="w-28">model</th>
              <th class="w-24">状态</th>
              <th class="w-32">最后消息</th>
              <th class="w-32">创建时间</th>
              <th class="w-44">操作</th>
            </tr>
          </template>
          <tr
            v-for="s in sessions"
            :key="s.id"
            :data-testid="`row-${s.id}`"
            class="transition-colors duration-150"
          >
            <td class="tabular-nums text-text-muted">#{{ s.id }}</td>
            <td class="tabular-nums text-text-muted">{{ s.userId }}</td>
            <td class="max-w-[240px]">
              <p class="truncate font-medium text-text" :title="s.title">{{ s.title }}</p>
            </td>
            <td class="text-text-muted">{{ s.model || '未指定' }}</td>
            <td>
              <Badge :data-testid="`session-status-${s.id}`" :variant="statusVariant(s.status)">
                {{ s.status }}
              </Badge>
            </td>
            <td class="tabular-nums text-text-muted">{{ formatDateTime(s.lastMessageAt) }}</td>
            <td class="tabular-nums text-text-muted">{{ formatDateTime(s.createdAt) }}</td>
            <td>
              <div class="flex items-center gap-1.5">
                <Button
                  variant="ghost"
                  size="sm"
                  :data-testid="`op-detail-${s.id}`"
                  @click="openDetail(s)"
                >
                  详情
                </Button>
                <!-- 关闭入口：仅 ACTIVE 行（CLOSED 重复关闭后端会 409）；先弹二次确认 -->
                <Button
                  v-if="s.status === 'ACTIVE'"
                  variant="outline"
                  size="sm"
                  :data-testid="`op-close-${s.id}`"
                  :disabled="closeSubmitting && closing?.id === s.id"
                  @click="requestClose(s)"
                >
                  <PhSpinnerGap
                    v-if="closeSubmitting && closing?.id === s.id"
                    class="h-3 w-3 animate-spin"
                  />
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
        </DataTable>
      </div>

      <!-- 分页器：左「共 N 条」右 上/下页 + 页码（设计 §2.6） -->
      <div class="flex items-center justify-between text-sm text-text-muted">
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
  </div>

  <!-- 会话详情回放 Drawer（公共组件：detail 拉取 + messages 只读流；仅超管入口展示） -->
  <ConversationReplayDrawer
    :open="detailOpen"
    :session-id="detailSession?.id ?? ''"
    :title="detailSession?.title ?? '会话回放'"
    :initial-status="detailSession?.status"
    @close="closeDetail"
  />

  <!-- 删除会话二次确认（级联软删消息 + Run，不可恢复）；外层 div 承载 dialog testid 契约 -->
  <div v-if="deleting" data-testid="session-del-dialog">
    <ConfirmDialog
      :open="!!deleting"
      title="删除会话"
      :description="`删除后该会话及其全部消息一并移除，此操作不可恢复。确认删除「${deleting.title}」？`"
      confirm-text="确认删除"
      :loading="deleteSubmitting"
      data-testid="confirm-session-del"
      @cancel="cancelDelete"
      @confirm="confirmDelete"
    />
  </div>

  <!-- 关闭会话二次确认（BUG-31：终止学生活跃对话属高影响操作，与删除同款弹窗防误触）；
       外层 div 承载 dialog testid 契约 -->
  <div v-if="closing" data-testid="session-close-dialog">
    <ConfirmDialog
      :open="!!closing"
      title="关闭会话"
      :description="`关闭后该学生的对话将被立即中断。确认关闭「${closing.title}」？`"
      confirm-text="确认关闭"
      :loading="closeSubmitting"
      data-testid="confirm-session-close"
      @cancel="cancelClose"
      @confirm="confirmClose"
    />
  </div>
</template>
