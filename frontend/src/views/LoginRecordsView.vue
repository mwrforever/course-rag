<script setup lang="ts">
/**
 * 登录记录页（设计 §2.4.7 登录记录，超管专属：路由 meta.roles 仅 SUPER_ADMIN）
 *
 * 能力清单：
 * 1. 筛选：userId / deviceType / status（ACTIVE/REVOKED/EXPIRED）；status 下拉即时生效，
 *    userId/deviceType 文本输入经查询按钮统一提交（携带全部参数并回第 1 页）
 * 2. 表格：#id / 用户 / 设备 / IP / 到期时间 / 状态 Badge
 *    （ACTIVE emerald / REVOKED amber / EXPIRED 中性）/ 时间 / 操作 [踢出设备]
 * 3. 踢出设备：仅 ACTIVE 行入口（已踢出/已过期无意义），二次确认（danger 实底）→
 *    revokeLoginRecord → toast → 刷新
 * 4. 四态：loading 骨架 / empty / error 横幅重试 / 正常 + 分页
 *
 * 契约要点：id/total 为 Long 字符串铁律；时间 ISO-8601 短格式；
 * 踢出设备仅超管可操作（路由层已拦截，页面不做二次角色判断）。
 *
 * 线程安全注意：全部状态为组件私有 ref，无跨实例共享可变状态。
 */
import { computed, reactive, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { PhSpinnerGap, PhWarningCircle } from '@phosphor-icons/vue'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ApiError, securityApi } from '@/lib/api'
import { showToast } from '@/lib/toast'
import { formatDateTime } from '@/lib/utils'

import type { LoginRecordStatus, SysLoginRecordVO } from '@/lib/types'

/** 每页条数（设计 §2.6 分页器） */
const PAGE_SIZE = 10

/** 状态筛选选项（设计 §2.4.7 三态枚举） */
const STATUS_OPTIONS: Array<{ value: LoginRecordStatus; label: string }> = [
  { value: 'ACTIVE', label: '有效' },
  { value: 'REVOKED', label: '已踢出' },
  { value: 'EXPIRED', label: '已过期' },
]

// ====================================================================
// 列表数据（筛选 + 分页，四态页面级收敛）
// ====================================================================

/** 筛选条件：status 即时生效；userId/deviceType 经查询按钮提交 */
const filters = reactive({ userId: '', deviceType: '', status: '' })
const pendingFilters = reactive({ userId: '', deviceType: '' })
const page = ref(1)

/** 查询键：筛选或页码任一变化即重查（vue-query 数据源，C.1.4） */
const queryKey = computed(() => [
  'admin-login-records',
  { userId: filters.userId, deviceType: filters.deviceType, status: filters.status },
  page.value,
])

const {
  data,
  isLoading,
  isError,
  error: queryError,
  refetch,
} = useQuery({
  queryKey,
  queryFn: () =>
    securityApi.loginRecords({
      page: page.value,
      size: PAGE_SIZE,
      ...(filters.userId ? { userId: filters.userId } : {}),
      ...(filters.deviceType ? { deviceType: filters.deviceType } : {}),
      ...(filters.status ? { status: filters.status } : {}),
    }),
})

/** 列表行数据：total 为 Long 字符串铁律 */
const records = computed(() => data.value?.records ?? [])
const total = computed(() => data.value?.total ?? '0')

/** 总页数：total 为 Long 字符串，转 number 后按 PAGE_SIZE 上取整 */
const totalPages = computed(() => Math.max(1, Math.ceil(Number(total.value) / PAGE_SIZE)))

/** 列表加载失败横幅文案（queryError 非空时透出；503 统一降级） */
const listError = computed(() =>
  isError.value ? messageOf(queryError.value, '登录记录加载失败，请稍后重试') : '',
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
function refreshRecords() {
  queryClient.invalidateQueries({ queryKey: ['admin-login-records'] })
}

/** status 下拉即时生效：写入筛选并回第 1 页（查询键变化自动重查） */
function onStatusChange(e: Event) {
  filters.status = (e.target as HTMLSelectElement).value
  page.value = 1
}

/** 查询按钮：userId/deviceType 草稿提交到筛选条件并回第 1 页（查询键变化自动重查） */
function applyFilters() {
  filters.userId = pendingFilters.userId.trim()
  filters.deviceType = pendingFilters.deviceType.trim()
  page.value = 1
}

/** 翻页：越界保护，页码变化自动重拉 */
function changePage(next: number) {
  if (next < 1 || next > totalPages.value) return
  page.value = next
}

/** 状态 Badge：ACTIVE emerald / REVOKED amber / EXPIRED 中性（设计 §2.4.7 三态） */
function statusVariant(status: LoginRecordStatus) {
  switch (status) {
    case 'ACTIVE':
      return 'success' as const
    case 'REVOKED':
      return 'warning' as const
    default:
      return 'default' as const
  }
}

// ====================================================================
// 踢出设备（仅 ACTIVE 行入口 + 二次确认 danger）
// ====================================================================

const kicking = ref<SysLoginRecordVO | null>(null)

/** 踢出设备提交（后端 K3：标记 REVOKED + jti 入黑名单，该设备全部刷新令牌即刻失效） */
const { isPending: kickSubmitting, mutate: confirmKickMutation } = useMutation({
  mutationFn: (id: string) => securityApi.revokeLoginRecord(id),
  onSuccess: () => {
    showToast('已将该设备踢出', 'success')
    kicking.value = null
    refreshRecords()
  },
  onError: (err) => {
    showToast(messageOf(err, '踢出设备失败，请稍后重试'), 'danger')
  },
})

function requestKick(r: SysLoginRecordVO) {
  kicking.value = r
}

function cancelKick() {
  if (kickSubmitting.value) return
  kicking.value = null
}

/** 确认踢出设备：提交中禁用按钮，完成/失败由 mutation 回调处理 */
function confirmKick() {
  if (!kicking.value) return
  confirmKickMutation(kicking.value.id)
}
</script>

<template>
  <!-- 筛选条：userId/deviceType 文本（查询按钮提交）+ status 下拉（即时生效） -->
  <div class="mb-4 flex flex-wrap items-center gap-2">
    <input
      v-model="pendingFilters.userId"
      data-testid="filter-user"
      type="text"
      aria-label="按用户 ID 筛选"
      placeholder="用户 ID"
      class="h-9 w-44 rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
    />
    <input
      v-model="pendingFilters.deviceType"
      data-testid="filter-device"
      type="text"
      aria-label="按设备类型筛选"
      placeholder="设备类型"
      class="h-9 w-44 rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
    />
    <select
      data-testid="filter-status"
      aria-label="按状态筛选"
      :value="filters.status"
      class="h-9 rounded-lg border border-border bg-surface px-2 text-sm text-text outline-none transition-colors duration-150 focus:border-brand focus:ring-2 focus:ring-brand/20"
      @change="onStatusChange"
    >
      <option value="">全部状态</option>
      <option v-for="opt in STATUS_OPTIONS" :key="opt.value" :value="opt.value">
        {{ opt.label }}
      </option>
    </select>
    <Button variant="outline" size="sm" data-testid="apply-filters" @click="applyFilters">
      查询
    </Button>
  </div>

  <!-- 错误态：页内横幅 + 重试（设计 §1.7） -->
  <div
    v-if="listError"
    role="alert"
    class="flex items-center justify-between gap-4 rounded-lg border border-danger/30 bg-red-50 px-4 py-3"
  >
    <span class="text-sm text-danger">{{ listError }}</span>
    <Button variant="outline" size="sm" data-testid="retry-records" @click="refetch">重试</Button>
  </div>

  <!-- 加载态：表格骨架屏（表头 + 5 行灰条，与最终表格同形） -->
  <div
    v-else-if="isLoading"
    data-testid="lr-skeleton"
    class="overflow-hidden rounded-xl border border-border bg-surface"
    aria-label="登录记录加载中"
  >
    <div class="flex items-center gap-6 border-b border-border bg-surface-2 px-4 py-2.5">
      <div v-for="i in 8" :key="`head-${i}`" class="h-3 w-20 animate-pulse rounded bg-slate-200" />
    </div>
    <div
      v-for="i in 5"
      :key="`row-${i}`"
      class="h-11 animate-pulse border-b border-border bg-slate-50"
    />
  </div>

  <!-- 空态：无登录记录文案（禁裸「暂无数据」） -->
  <div
    v-else-if="records.length === 0"
    class="flex flex-col items-center justify-center rounded-xl border border-dashed border-border bg-surface py-14 text-center"
  >
    <PhWarningCircle class="h-8 w-8 text-text-subtle" />
    <p class="mt-3 text-sm font-medium text-text">还没有登录记录</p>
    <p class="mt-1 text-xs text-text-muted">用户登录后生成的设备会话记录将在此展示</p>
  </div>

  <!-- 正常态：分页表格（#id/用户/设备/IP/到期/状态/时间/操作） -->
  <template v-else>
    <div class="overflow-hidden rounded-xl border border-border bg-surface">
      <table data-testid="lr-table" class="w-full text-sm">
        <thead class="border-b border-border bg-surface-2 text-left text-xs text-text-muted">
          <tr>
            <th class="w-20 px-4 py-2.5 font-medium">#id</th>
            <th class="w-28 px-4 py-2.5 font-medium">用户</th>
            <th class="w-32 px-4 py-2.5 font-medium">设备</th>
            <th class="w-36 px-4 py-2.5 font-medium">IP</th>
            <th class="w-32 px-4 py-2.5 font-medium">到期时间</th>
            <th class="w-24 px-4 py-2.5 font-medium">状态</th>
            <th class="w-32 px-4 py-2.5 font-medium">时间</th>
            <th class="w-28 px-4 py-2.5 text-right font-medium">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="r in records"
            :key="r.id"
            :data-testid="`row-${r.id}`"
            class="h-11 border-b border-border last:border-b-0 transition-colors duration-150 hover:bg-surface-2"
          >
            <td class="px-4 tabular-nums text-text-muted">#{{ r.id }}</td>
            <td class="px-4 tabular-nums text-text-muted">{{ r.userId }}</td>
            <td class="px-4 text-text-muted">{{ r.deviceType }}</td>
            <td class="px-4 tabular-nums text-text-muted">{{ r.ipAddress }}</td>
            <td :title="formatDateTime(r.expiresAt)" class="px-4 tabular-nums text-text-muted">
              {{ formatDateTime(r.expiresAt) }}
            </td>
            <td class="px-4">
              <Badge :data-testid="`lr-status-${r.id}`" :variant="statusVariant(r.status)">
                {{ r.status }}
              </Badge>
            </td>
            <td class="px-4 tabular-nums text-text-muted">{{ formatDateTime(r.createdAt) }}</td>
            <td class="px-4 text-right">
              <!-- 踢出入口：仅 ACTIVE 行（REVOKED/EXPIRED 已无有效令牌） -->
              <Button
                v-if="r.status === 'ACTIVE'"
                variant="danger"
                size="sm"
                :data-testid="`op-kick-${r.id}`"
                @click="requestKick(r)"
              >
                踢出设备
              </Button>
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

  <!-- 踢出设备二次确认（danger 实底，不可恢复：jti 入黑名单立即失效） -->
  <div
    v-if="kicking"
    data-testid="kick-dialog"
    class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4"
    @keydown.esc="cancelKick"
    @click.self="cancelKick"
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
          <h2 class="text-base font-semibold text-text">踢出设备</h2>
          <p class="mt-2 text-sm leading-relaxed text-text-muted">
            将立即吊销该设备的全部登录令牌（Access 与 Refresh），
            <span class="font-medium text-danger">此操作不可恢复</span>。确认踢出设备（{{
              kicking.deviceType
            }}，{{ kicking.ipAddress }}）？
          </p>
        </div>
      </div>
      <div class="mt-5 flex justify-end gap-2">
        <Button
          variant="outline"
          :disabled="kickSubmitting"
          data-testid="cancel-kick"
          @click="cancelKick"
        >
          取消
        </Button>
        <Button
          variant="danger"
          data-testid="confirm-kick"
          :disabled="kickSubmitting"
          @click="confirmKick"
        >
          <PhSpinnerGap v-if="kickSubmitting" class="h-4 w-4 animate-spin" />
          {{ kickSubmitting ? '踢出中' : '确认踢出' }}
        </Button>
      </div>
    </div>
  </div>
</template>
