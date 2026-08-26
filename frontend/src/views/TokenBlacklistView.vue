<script setup lang="ts">
/**
 * Token 黑名单页（设计 §2.4.7 黑名单，超管专属：路由 meta.roles 仅 SUPER_ADMIN）
 *
 * 能力清单：
 * 1. 筛选：userId / jti / tokenType（查询按钮统一提交 → 携带参数并回第 1 页）
 * 2. 表格：#id / jti / tokenType Badge（ACCESS brand / REFRESH 中性）/ userId /
 *    reason / expiresAt / createdAt / 操作 移除（二次确认）
 * 3. 手动加入表单（查询参数传参）：jti 与 tokenType、userId 必填，expiresAt 可选
 *    （缺省不携带）→ addBlacklist({jti, tokenType, userId, reason:'MANUAL_REVOKE', expiresAt?})
 * 4. [清理过期]：cleanupBlacklist → toast 展示 cleaned 数 → 刷新
 * 5. 四态：loading 骨架 / empty / error 横幅重试 / 正常 + 分页
 *
 * 契约要点：id/total 为 Long 字符串铁律；cleaned 为 Integer（K7 返回 Map<String,Integer>）；
 * 后端 K5 全参数走 @RequestParam（查询参数传参，axios params 而非 data）。
 *
 * 线程安全注意：全部状态为组件私有 ref，无跨实例共享可变状态。
 */
import { computed, reactive, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { z } from 'zod'
import { PhPlus, PhSpinnerGap, PhWarningCircle } from '@phosphor-icons/vue'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ApiError, securityApi } from '@/lib/api'
import { showToast } from '@/lib/toast'
import { formatDateTime } from '@/lib/utils'

import type { SysTokenBlacklistVO } from '@/lib/types'

/** 每页条数（设计 §2.6 分页器） */
const PAGE_SIZE = 10

// ====================================================================
// 列表数据（筛选 + 分页，四态页面级收敛）
// ====================================================================

/** 筛选条件：userId/jti/tokenType 经查询按钮提交 */
const filters = reactive({ userId: '', jti: '', tokenType: '' })
const pendingFilters = reactive({ userId: '', jti: '', tokenType: '' })
const page = ref(1)

/** 查询键：筛选或页码任一变化即重查（vue-query 数据源，C.1.4） */
const queryKey = computed(() => [
  'admin-token-blacklist',
  { userId: filters.userId, jti: filters.jti, tokenType: filters.tokenType },
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
    securityApi.blacklist({
      page: page.value,
      size: PAGE_SIZE,
      ...(filters.userId ? { userId: filters.userId } : {}),
      ...(filters.jti ? { jti: filters.jti } : {}),
      ...(filters.tokenType ? { tokenType: filters.tokenType } : {}),
    }),
})

/** 列表行数据：total 为 Long 字符串铁律 */
const items = computed(() => data.value?.records ?? [])
const total = computed(() => data.value?.total ?? '0')

/** 总页数：total 为 Long 字符串，转 number 后按 PAGE_SIZE 上取整 */
const totalPages = computed(() => Math.max(1, Math.ceil(Number(total.value) / PAGE_SIZE)))

/** 列表加载失败横幅文案（queryError 非空时透出；503 统一降级） */
const listError = computed(() =>
  isError.value ? messageOf(queryError.value, '黑名单加载失败，请稍后重试') : '',
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
function refreshItems() {
  queryClient.invalidateQueries({ queryKey: ['admin-token-blacklist'] })
}

/** 查询按钮：三筛选草稿提交并回第 1 页（查询键变化自动重查） */
function applyFilters() {
  filters.userId = pendingFilters.userId.trim()
  filters.jti = pendingFilters.jti.trim()
  filters.tokenType = pendingFilters.tokenType
  page.value = 1
}

/** 翻页：越界保护，页码变化自动重拉 */
function changePage(next: number) {
  if (next < 1 || next > totalPages.value) return
  page.value = next
}

/** tokenType Badge：ACCESS 强调 / REFRESH 中性（双型区分） */
function typeVariant(tokenType: string) {
  return tokenType === 'ACCESS' ? ('brand' as const) : ('default' as const)
}

// ====================================================================
// 手动加入（查询参数传参表单：jti/tokenType/userId 必填，expiresAt 可选）
// ====================================================================

/** 手动加入校验 schema：jti 非空 + userId 非空（tokenType 下拉恒有值不需要校验） */
const addSchema = z.object({
  jti: z.string().trim().min(1, '请输入 JTI'),
  userId: z.string().trim().min(1, '请输入用户 ID'),
})

const addOpen = ref(false)
const addForm = reactive({ jti: '', tokenType: 'ACCESS', userId: '', expiresAt: '' })
const addErrors = reactive({ jti: '', userId: '' })

/** 手动加入提交（成功后失效列表键） */
const { isPending: addSubmitting, mutate: submitAddMutation } = useMutation({
  mutationFn: (payload: { jti: string; tokenType: string; userId: string; expiresAt: string }) =>
    securityApi.addBlacklist({
      jti: payload.jti,
      tokenType: payload.tokenType,
      userId: payload.userId,
      reason: 'MANUAL_REVOKE',
      ...(payload.expiresAt ? { expiresAt: payload.expiresAt } : {}),
    }),
  onSuccess: () => {
    showToast('已加入黑名单', 'success')
    addOpen.value = false
    addForm.jti = ''
    addForm.userId = ''
    addForm.expiresAt = ''
    refreshItems()
  },
  onError: (err) => {
    showToast(messageOf(err, '加入黑名单失败，请稍后重试'), 'danger')
  },
})

function openAdd() {
  addOpen.value = true
  addErrors.jti = ''
  addErrors.userId = ''
}

function closeAdd() {
  if (addSubmitting.value) return
  addOpen.value = false
}

/**
 * 提交手动加入：zod 前置校验 → 走 mutation（后端 K5 @RequestParam 查询参数传参）
 *
 * 参数契约：reason 恒 MANUAL_REVOKE；expiresAt 未填不携带（后端缺省 7 天后过期）；
 * 填写时将 datetime-local「YYYY-MM-DDTHH:mm」补秒位为可解析的「YYYY-MM-DDTHH:mm:00」。
 */
function submitAdd() {
  const parsed = addSchema.safeParse(addForm)
  if (!parsed.success) {
    // zod v4 issues.path 为字段路径数组：includes 判定字段归属，就地分列报错
    const issues = parsed.error.issues
    addErrors.jti = issues.find((i) => i.path.includes('jti'))?.message ?? ''
    addErrors.userId = issues.find((i) => i.path.includes('userId'))?.message ?? ''
    return
  }
  addErrors.jti = ''
  addErrors.userId = ''
  submitAddMutation({
    jti: addForm.jti.trim(),
    tokenType: addForm.tokenType,
    userId: addForm.userId.trim(),
    expiresAt: addForm.expiresAt ? `${addForm.expiresAt}:00` : '',
  })
}

// ====================================================================
// 移除（二次确认）与清理过期
// ====================================================================

const removing = ref<SysTokenBlacklistVO | null>(null)

/** 移除黑名单记录提交（Token 过期后清理，二次确认防误删；成功后失效列表键） */
const { isPending: removeSubmitting, mutate: confirmRemoveMutation } = useMutation({
  mutationFn: (id: string) => securityApi.removeBlacklist(id),
  onSuccess: () => {
    showToast('已从黑名单移除', 'success')
    removing.value = null
    if (items.value.length === 1 && page.value > 1) {
      page.value -= 1
    } else {
      queryClient.invalidateQueries({ queryKey: ['admin-token-blacklist'] })
    }
  },
  onError: (err) => {
    showToast(messageOf(err, '移除失败，请稍后重试'), 'danger')
  },
})

function requestRemove(t: SysTokenBlacklistVO) {
  removing.value = t
}

function cancelRemove() {
  if (removeSubmitting.value) return
  removing.value = null
}

/** 确认移除：提交中禁用按钮，完成/失败由 mutation 回调处理 */
function confirmRemove() {
  if (!removing.value) return
  confirmRemoveMutation(removing.value.id)
}

/** 清理过期提交（toast 按 cleaned 数分级；成功后失效列表键） */
const { isPending: cleaning, mutate: cleanupExpiredMutation } = useMutation({
  mutationFn: () => securityApi.cleanupBlacklist(),
  onSuccess: (res) => {
    showToast(`已清理 ${res.cleaned} 条过期记录`, res.cleaned > 0 ? 'success' : 'info')
    refreshItems()
  },
  onError: (err) => {
    showToast(messageOf(err, '清理失败，请稍后重试'), 'danger')
  },
})

/** 清理过期入口：提交中禁用按钮，完成/失败由 mutation 回调处理 */
function cleanupExpired() {
  cleanupExpiredMutation()
}
</script>

<template>
  <!-- 页头操作行：手动加入 + 清理过期（两入口常驻页头） -->
  <div class="mb-4 flex items-center justify-between">
    <p class="text-sm text-text-muted">
      被禁用的 Token 记录（Access/Refresh），踢出设备与手动加入汇聚于此
    </p>
    <div class="flex items-center gap-2">
      <Button
        variant="outline"
        size="sm"
        data-testid="cleanup"
        :disabled="cleaning"
        @click="cleanupExpired"
      >
        <PhSpinnerGap v-if="cleaning" class="h-4 w-4 animate-spin" />
        清理过期
      </Button>
      <Button data-testid="open-add" @click="openAdd">
        <PhPlus class="h-4 w-4" />
        手动加入
      </Button>
    </div>
  </div>

  <!-- 筛选条：userId/jti/tokenType（查询按钮统一提交） -->
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
      v-model="pendingFilters.jti"
      data-testid="filter-jti"
      type="text"
      aria-label="按 JTI 筛选"
      placeholder="JTI"
      class="h-9 w-52 rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
    />
    <select
      v-model="pendingFilters.tokenType"
      data-testid="filter-type"
      aria-label="按令牌类型筛选"
      class="h-9 rounded-lg border border-border bg-surface px-2 text-sm text-text outline-none transition-colors duration-150 focus:border-brand focus:ring-2 focus:ring-brand/20"
    >
      <option value="">全部类型</option>
      <option value="ACCESS">ACCESS</option>
      <option value="REFRESH">REFRESH</option>
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
    <Button variant="outline" size="sm" data-testid="retry-blacklist" @click="refetch">重试</Button>
  </div>

  <!-- 加载态：表格骨架屏（表头 + 5 行灰条，与最终表格同形） -->
  <div
    v-else-if="isLoading"
    data-testid="tb-skeleton"
    class="overflow-hidden rounded-xl border border-border bg-surface"
    aria-label="黑名单加载中"
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

  <!-- 空态：无黑名单项文案（禁裸「暂无数据」） -->
  <div
    v-else-if="items.length === 0"
    class="flex flex-col items-center justify-center rounded-xl border border-dashed border-border bg-surface py-14 text-center"
  >
    <PhWarningCircle class="h-8 w-8 text-text-subtle" />
    <p class="mt-3 text-sm font-medium text-text">黑名单为空</p>
    <p class="mt-1 text-xs text-text-muted">被吊销的 Token 会在此保留至过期，也可手动加入</p>
  </div>

  <!-- 正常态：分页表格（#id/jti/tokenType/userId/reason/到期/创建/操作） -->
  <template v-else>
    <div class="overflow-hidden rounded-xl border border-border bg-surface">
      <table data-testid="tb-table" class="w-full text-sm">
        <thead class="border-b border-border bg-surface-2 text-left text-xs text-text-muted">
          <tr>
            <th class="w-20 px-4 py-2.5 font-medium">#id</th>
            <th class="px-4 py-2.5 font-medium">jti</th>
            <th class="w-28 px-4 py-2.5 font-medium">tokenType</th>
            <th class="w-28 px-4 py-2.5 font-medium">用户</th>
            <th class="w-32 px-4 py-2.5 font-medium">reason</th>
            <th class="w-32 px-4 py-2.5 font-medium">到期时间</th>
            <th class="w-32 px-4 py-2.5 font-medium">创建时间</th>
            <th class="w-24 px-4 py-2.5 text-right font-medium">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="t in items"
            :key="t.id"
            :data-testid="`row-${t.id}`"
            class="h-11 border-b border-border last:border-b-0 transition-colors duration-150 hover:bg-surface-2"
          >
            <td class="px-4 tabular-nums text-text-muted">#{{ t.id }}</td>
            <td class="max-w-[200px] truncate px-4 font-mono text-xs text-text" :title="t.jti">
              {{ t.jti }}
            </td>
            <td class="px-4">
              <Badge :data-testid="`tb-type-${t.id}`" :variant="typeVariant(t.tokenType)">
                {{ t.tokenType }}
              </Badge>
            </td>
            <td class="px-4 tabular-nums text-text-muted">{{ t.userId }}</td>
            <td class="px-4 text-text-muted">{{ t.reason }}</td>
            <td class="px-4 tabular-nums text-text-muted">{{ formatDateTime(t.expiresAt) }}</td>
            <td class="px-4 tabular-nums text-text-muted">{{ formatDateTime(t.createdAt) }}</td>
            <td class="px-4 text-right">
              <Button
                variant="danger"
                size="sm"
                :data-testid="`op-remove-${t.id}`"
                @click="requestRemove(t)"
              >
                移除
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

  <!-- 手动加入 Dialog（查询参数传参表单：jti/tokenType/userId 必填，expiresAt 可选） -->
  <div
    v-if="addOpen"
    data-testid="blacklist-add-dialog"
    class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4"
    @keydown.esc="closeAdd"
    @click.self="closeAdd"
  >
    <div
      class="w-full max-w-[480px] rounded-xl border border-border bg-surface p-6 shadow-md"
      role="dialog"
      aria-modal="true"
      @click.stop
    >
      <h2 class="text-base font-semibold text-text">手动加入黑名单</h2>
      <p class="mt-1 text-xs text-text-muted">
        手动吊销指定 jti 的令牌（reason 固定 MANUAL_REVOKE），缺省 7 天后过期
      </p>
      <form
        data-testid="blacklist-add-form"
        class="mt-5 space-y-4"
        novalidate
        @submit.prevent="submitAdd"
      >
        <div>
          <label for="add-jti" class="mb-1.5 block text-sm font-medium text-text">
            JTI <span class="text-danger">*</span>
          </label>
          <input
            id="add-jti"
            v-model="addForm.jti"
            data-testid="add-jti"
            type="text"
            aria-label="令牌 JTI"
            placeholder="被吊销令牌的 JWT ID"
            class="h-10 w-full rounded-lg border border-border bg-surface px-3 font-mono text-sm text-text outline-none transition-colors duration-150 placeholder:font-sans placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
          />
          <p v-if="addErrors.jti" class="mt-1 text-xs text-danger">{{ addErrors.jti }}</p>
        </div>
        <div>
          <label for="add-type" class="mb-1.5 block text-sm font-medium text-text">
            tokenType <span class="text-danger">*</span>
          </label>
          <select
            id="add-type"
            v-model="addForm.tokenType"
            data-testid="add-type"
            class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 focus:border-brand focus:ring-2 focus:ring-brand/20"
          >
            <option value="ACCESS">ACCESS</option>
            <option value="REFRESH">REFRESH</option>
          </select>
        </div>
        <div>
          <label for="add-user" class="mb-1.5 block text-sm font-medium text-text">
            用户 ID <span class="text-danger">*</span>
          </label>
          <input
            id="add-user"
            v-model="addForm.userId"
            data-testid="add-user"
            type="text"
            aria-label="用户 ID"
            placeholder="令牌所属用户"
            class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
          />
          <p v-if="addErrors.userId" class="mt-1 text-xs text-danger">{{ addErrors.userId }}</p>
        </div>
        <div>
          <label for="add-expires" class="mb-1.5 block text-sm font-medium text-text">
            过期时间（可选）
          </label>
          <input
            id="add-expires"
            v-model="addForm.expiresAt"
            data-testid="add-expires"
            type="datetime-local"
            aria-label="过期时间"
            class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 focus:border-brand focus:ring-2 focus:ring-brand/20"
          />
        </div>
        <div class="flex justify-end gap-2 pt-2">
          <Button variant="outline" :disabled="addSubmitting" @click="closeAdd">取消</Button>
          <Button type="submit" data-testid="submit-add" :disabled="addSubmitting">
            <PhSpinnerGap v-if="addSubmitting" class="h-4 w-4 animate-spin" />
            {{ addSubmitting ? '加入中' : '加入黑名单' }}
          </Button>
        </div>
      </form>
    </div>
  </div>

  <!-- 移除黑名单二次确认（安全保护记录，防误删） -->
  <div
    v-if="removing"
    data-testid="blacklist-del-dialog"
    class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4"
    @keydown.esc="cancelRemove"
    @click.self="cancelRemove"
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
          <h2 class="text-base font-semibold text-text">移除黑名单记录</h2>
          <p class="mt-2 text-sm leading-relaxed text-text-muted">
            移除后该 jti 的令牌将不再被拦截（Token 过期后清理记录属正常运维）。 确认移除
            {{ removing.jti }}？
          </p>
        </div>
      </div>
      <div class="mt-5 flex justify-end gap-2">
        <Button variant="outline" :disabled="removeSubmitting" @click="cancelRemove">取消</Button>
        <Button
          variant="danger"
          data-testid="confirm-blacklist-del"
          :disabled="removeSubmitting"
          @click="confirmRemove"
        >
          <PhSpinnerGap v-if="removeSubmitting" class="h-4 w-4 animate-spin" />
          {{ removeSubmitting ? '移除中' : '确认移除' }}
        </Button>
      </div>
    </div>
  </div>
</template>
