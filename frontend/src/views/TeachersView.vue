<script setup lang="ts">
/**
 * 教师管理（UI 重构 2026-08-25：从 UsersView 拆分，职责=教师账号域）
 *
 * 能力：教师账号分页列表（用户名/显示名/状态/创建时间）+ 添加教师 + 编辑显示名 +
 * 重置密码（zod ≥6 + 两次一致）+ 禁用/启用（二次确认）+ 删除（二次确认）。
 * 角色固定 TEACHER：本页只管理教师，不再承载教师/账号混域（用户拍板剥离）。
 * 权限矩阵：自身行禁用/启用入口隐藏（防自锁，后端 A7 同时拒绝）。
 */
import { computed, reactive, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { z } from 'zod'
import { PhSpinnerGap, PhUserPlus, PhWarningCircle } from '@phosphor-icons/vue'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ApiError, userApi } from '@/lib/api'
import { showToast } from '@/lib/toast'
import { formatDateTime } from '@/lib/utils'
import { useAuthStore } from '@/stores/auth'

import type { UserDTO, UserStatus } from '@/lib/types'

/** 每页条数 */
const PAGE_SIZE = 10

const auth = useAuthStore()

/** 创建教师账号表单校验 schema：username/displayName 非空、password ≥6 位 */
const createSchema = z.object({
  username: z.string().trim().min(1, '请输入用户名'),
  password: z.string().min(1, '请输入密码').min(6, '密码至少 6 位'),
  displayName: z.string().trim().min(1, '请输入显示名'),
})

/** 页码：查询键组成之一，变化自动触发新查询 */
const page = ref(1)

/** 查询键：页码变化即重拉当前页（vue-query 数据源，C.1.4） */
const queryKey = computed(() => ['admin-teachers', page.value])

const {
  data,
  isLoading,
  isError,
  error: queryError,
  refetch,
} = useQuery({
  queryKey,
  queryFn: () => userApi.list({ page: page.value, size: PAGE_SIZE, role: 'TEACHER' }),
})

/** 列表行数据：total 为 Long 字符串铁律 */
const teachers = computed(() => data.value?.records ?? [])
const total = computed(() => data.value?.total ?? '0')

/** 总页数：total 为 Long 字符串，转 number 后按 PAGE_SIZE 上取整 */
const totalPages = computed(() => Math.max(1, Math.ceil(Number(total.value) / PAGE_SIZE)))

/** 列表加载失败横幅文案（queryError 非空时透出；503 统一降级） */
const listError = computed(() =>
  isError.value ? messageOf(queryError.value, '教师列表加载失败，请稍后重试') : '',
)

/** 接口错误分级文案 */
function messageOf(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    return err.code === 503 ? '服务暂时不可用，请稍后重试' : err.message
  }
  return fallback
}

/** 翻页：越界保护，页码变化自动重拉 */
function changePage(next: number) {
  if (next < 1 || next > totalPages.value) return
  page.value = next
}

const queryClient = useQueryClient()

/** 写操作成功后的列表刷新：不减少行数的操作直接按查询键失效重拉（删除类见删除 mutation 内联回退） */
function refreshTeachers() {
  queryClient.invalidateQueries({ queryKey: ['admin-teachers'] })
}

/** 自身行判定：当前登录用户（auth.userId）的行禁用/启用按钮隐藏（防自锁） */
function isSelf(u: UserDTO): boolean {
  return auth.userId === u.id
}

/** 状态 Badge：ACTIVE success / DISABLED danger */
function statusVariant(status: UserStatus) {
  return status === 'ACTIVE' ? ('success' as const) : ('danger' as const)
}

// ============ 添加教师 Dialog（角色固定 TEACHER，无角色选择器） ============

const addOpen = ref(false)
const addForm = reactive({ username: '', password: '', displayName: '' })
const addErrors = reactive({ username: '', password: '', displayName: '' })

/** 创建教师提交（isPending 驱动按钮禁用与拦截；成功后失效列表键） */
const { isPending: addSubmitting, mutate: submitAddMutation } = useMutation({
  mutationFn: (payload: { username: string; password: string; displayName: string }) =>
    userApi.create({ ...payload, role: 'TEACHER' }),
  onSuccess: () => {
    showToast('教师账号已创建', 'success')
    addOpen.value = false
    refreshTeachers()
  },
  onError: (err) => {
    showToast(messageOf(err, '创建教师失败，请稍后重试'), 'danger')
  },
})

function openAdd() {
  addForm.username = ''
  addForm.password = ''
  addForm.displayName = ''
  resetAddErrors()
  addOpen.value = true
}

function resetAddErrors() {
  addErrors.username = ''
  addErrors.password = ''
  addErrors.displayName = ''
}

function closeAdd() {
  if (addSubmitting.value) return
  addOpen.value = false
}

/** 提交创建教师：zod 前置校验通过后走 mutation（role 恒 TEACHER） */
function submitAdd() {
  const parsed = createSchema.safeParse(addForm)
  if (!parsed.success) {
    const issues = parsed.error.issues
    addErrors.username = issues.find((i) => i.path.includes('username'))?.message ?? ''
    addErrors.password = issues.find((i) => i.path.includes('password'))?.message ?? ''
    addErrors.displayName = issues.find((i) => i.path.includes('displayName'))?.message ?? ''
    return
  }
  addErrors.username = ''
  addErrors.password = ''
  addErrors.displayName = ''
  submitAddMutation({
    username: addForm.username.trim(),
    password: addForm.password,
    displayName: addForm.displayName.trim(),
  })
}

// ============ 编辑 displayName Dialog ============

const editing = ref<UserDTO | null>(null)
const editName = ref('')
const editError = ref('')

/** 保存显示名提交（成功后失效列表键） */
const { isPending: editSubmitting, mutate: saveEditMutation } = useMutation({
  mutationFn: (payload: { id: string; displayName: string }) =>
    userApi.update(payload.id, { displayName: payload.displayName }),
  onSuccess: () => {
    showToast('显示名已更新', 'success')
    editing.value = null
    refreshTeachers()
  },
  onError: (err) => {
    showToast(messageOf(err, '保存失败，请稍后重试'), 'danger')
  },
})

function openEdit(u: UserDTO) {
  editing.value = u
  editName.value = u.displayName
  editError.value = ''
}

function closeEdit() {
  if (editSubmitting.value) return
  editing.value = null
}

/** 保存显示名：必填校验通过后走 mutation */
function submitEdit() {
  if (!editing.value) return
  const name = editName.value.trim()
  if (!name) {
    editError.value = '请输入显示名'
    return
  }
  saveEditMutation({ id: editing.value.id, displayName: name })
}

// ============ 重置密码 Dialog（zod ≥6 + 两次输入一致） ============

const resetSchema = z.object({ newPassword: z.string().min(6, '新密码至少 6 位') })

const resetting = ref<UserDTO | null>(null)
const newPassword = ref('')
const confirmPassword = ref('')
const resetError = ref('')

/** 重置密码提交（成功后仅 toast + 关闭，无列表刷新——原逻辑保持） */
const { isPending: resetSubmitting, mutate: resetPasswordMutation } = useMutation({
  mutationFn: (payload: { id: string; newPassword: string }) =>
    userApi.resetPassword(payload.id, { newPassword: payload.newPassword }),
  onSuccess: () => {
    showToast('密码已重置', 'success')
    resetting.value = null
  },
  onError: (err) => {
    showToast(messageOf(err, '重置密码失败，请稍后重试'), 'danger')
  },
})

function openReset(u: UserDTO) {
  resetting.value = u
  newPassword.value = ''
  confirmPassword.value = ''
  resetError.value = ''
}

function closeReset() {
  if (resetSubmitting.value) return
  resetting.value = null
}

/** 提交重置密码：zod ≥6 前置校验 → 两次输入一致 → 走 mutation */
function submitReset() {
  if (!resetting.value) return
  const parsed = resetSchema.safeParse({ newPassword: newPassword.value })
  if (!parsed.success) {
    resetError.value = parsed.error.issues[0].message
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    resetError.value = '两次输入的密码不一致'
    return
  }
  resetPasswordMutation({ id: resetting.value.id, newPassword: newPassword.value })
}

// ============ 禁用/启用（二次确认） ============

const statusTarget = ref<UserDTO | null>(null)
const statusNext = ref<UserStatus>('DISABLED')

/** 禁用/启用提交（成功后失效列表键） */
const { isPending: statusSubmitting, mutate: toggleStatusMutation } = useMutation({
  mutationFn: (payload: { id: string; status: UserStatus }) =>
    userApi.updateStatus(payload.id, { status: payload.status }),
  onSuccess: () => {
    showToast(statusNext.value === 'DISABLED' ? '已禁用该教师' : '已启用该教师', 'success')
    statusTarget.value = null
    refreshTeachers()
  },
  onError: (err) => {
    showToast(messageOf(err, '操作失败，请稍后重试'), 'danger')
  },
})

function requestStatusToggle(u: UserDTO) {
  statusTarget.value = u
  statusNext.value = u.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
}

function cancelStatusToggle() {
  if (statusSubmitting.value) return
  statusTarget.value = null
}

function confirmStatusToggle() {
  if (!statusTarget.value) return
  toggleStatusMutation({ id: statusTarget.value.id, status: statusNext.value })
}

// ============ 删除（二次确认，不可恢复） ============

const deleting = ref<UserDTO | null>(null)

/** 删除教师提交（成功后失效列表键；删除末页最后一条会留空页——回退一页防空页） */
const { isPending: deleteSubmitting, mutate: deleteMutation } = useMutation({
  mutationFn: (id: string) => userApi.remove(id),
  onSuccess: () => {
    showToast('教师已删除', 'success')
    deleting.value = null
    if (teachers.value.length === 1 && page.value > 1) {
      page.value -= 1
    } else {
      queryClient.invalidateQueries({ queryKey: ['admin-teachers'] })
    }
  },
  onError: (err) => {
    showToast(messageOf(err, '删除失败，请稍后重试'), 'danger')
  },
})

function requestDelete(u: UserDTO) {
  deleting.value = u
}

function cancelDelete() {
  if (deleteSubmitting.value) return
  deleting.value = null
}

function confirmDelete() {
  if (!deleting.value) return
  deleteMutation(deleting.value.id)
}
</script>

<template>
  <div>
    <!-- 页头操作行：添加教师（本页职责=教师账号域，无教师入口） -->
    <div class="mb-4 flex items-center justify-between">
      <p class="text-sm text-text-muted">管理教师账号、状态与密码</p>
      <Button data-testid="add-teacher" @click="openAdd">
        <PhUserPlus class="h-4 w-4" />
        添加教师
      </Button>
    </div>

    <!-- 错误态：页内横幅 + 重试 -->
    <div
      v-if="listError"
      role="alert"
      class="flex items-center justify-between gap-4 rounded-xl border border-danger/30 bg-danger/5 px-4 py-3"
    >
      <span class="text-sm text-danger">{{ listError }}</span>
      <Button variant="outline" size="sm" data-testid="retry-teachers" @click="refetch"
        >重试</Button
      >
    </div>

    <!-- 加载态：表格骨架屏 -->
    <div
      v-else-if="isLoading"
      data-testid="teacher-skeleton"
      class="overflow-hidden rounded-xl border border-border bg-surface"
      aria-label="教师列表加载中"
    >
      <div class="flex items-center gap-6 border-b border-border bg-surface-2 px-4 py-2.5">
        <div
          v-for="i in 5"
          :key="`head-${i}`"
          class="h-3 w-20 animate-pulse rounded bg-surface-2"
        />
      </div>
      <div
        v-for="i in 5"
        :key="`row-${i}`"
        class="h-11 animate-pulse border-b border-border bg-surface"
      />
    </div>

    <!-- 空态：语义文案 + 添加入口 -->
    <div
      v-else-if="teachers.length === 0"
      class="flex flex-col items-center justify-center rounded-xl border border-dashed border-border bg-surface py-14 text-center"
    >
      <PhWarningCircle class="h-8 w-8 text-text-subtle" />
      <p class="mt-3 text-sm font-medium text-text">还没有教师</p>
      <p class="mt-1 text-xs text-text-muted">创建教师账号后即可由教师登录使用课程助手</p>
      <div class="mt-4">
        <Button data-testid="add-teacher-empty" @click="openAdd">添加教师</Button>
      </div>
    </div>

    <!-- 正常态：分页表格 -->
    <template v-else>
      <div class="overflow-hidden rounded-xl border border-border bg-surface">
        <table data-testid="teacher-table" class="w-full text-sm">
          <thead class="border-b border-border bg-surface-2 text-left text-xs text-text-muted">
            <tr>
              <th class="px-4 py-2.5 font-medium">用户名</th>
              <th class="px-4 py-2.5 font-medium">显示名</th>
              <th class="w-28 px-4 py-2.5 font-medium">状态</th>
              <th class="w-40 px-4 py-2.5 font-medium">创建时间</th>
              <th class="w-64 px-4 py-2.5 text-right font-medium">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="u in teachers"
              :key="u.id"
              :data-testid="`row-${u.id}`"
              class="h-11 border-b border-border transition-colors duration-150 last:border-b-0 hover:bg-surface-2"
            >
              <td class="px-4 font-medium text-text">{{ u.username }}</td>
              <td class="px-4 text-text-muted">{{ u.displayName }}</td>
              <td class="px-4">
                <Badge :data-testid="`user-status-${u.id}`" :variant="statusVariant(u.status)">
                  {{ u.status }}
                </Badge>
              </td>
              <td class="px-4 tabular-nums text-text-muted">{{ formatDateTime(u.createdAt) }}</td>
              <td class="px-4 text-right">
                <div class="flex items-center justify-end gap-1">
                  <Button
                    variant="ghost"
                    size="sm"
                    :data-testid="`op-edit-${u.id}`"
                    @click="openEdit(u)"
                  >
                    编辑
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    :data-testid="`op-reset-${u.id}`"
                    @click="openReset(u)"
                  >
                    重置密码
                  </Button>
                  <Button
                    v-if="!isSelf(u) && u.status === 'ACTIVE'"
                    variant="danger"
                    size="sm"
                    :data-testid="`op-disable-${u.id}`"
                    @click="requestStatusToggle(u)"
                  >
                    禁用
                  </Button>
                  <Button
                    v-else-if="!isSelf(u) && u.status === 'DISABLED'"
                    variant="outline"
                    size="sm"
                    :data-testid="`op-enable-${u.id}`"
                    @click="requestStatusToggle(u)"
                  >
                    启用
                  </Button>
                  <Button
                    variant="danger"
                    size="sm"
                    :data-testid="`op-delete-${u.id}`"
                    @click="requestDelete(u)"
                  >
                    删除
                  </Button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 分页器 -->
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

    <!-- 添加教师 Dialog（角色固定 TEACHER，无角色选择器） -->
    <div
      v-if="addOpen"
      data-testid="add-user-dialog"
      class="fixed inset-0 z-50 flex items-center justify-center bg-overlay p-4"
      @keydown.esc="closeAdd"
      @click.self="closeAdd"
    >
      <div
        class="animate-menu-in w-full max-w-[480px] rounded-xl border border-border bg-surface p-6 shadow-lg"
        role="dialog"
        aria-modal="true"
        @click.stop
      >
        <h2 class="text-base font-semibold text-text">添加教师</h2>
        <form data-testid="add-form" class="mt-5 space-y-4" novalidate @submit.prevent="submitAdd">
          <div>
            <label for="add-username" class="mb-1.5 block text-sm font-medium text-text">
              用户名 <span class="text-danger">*</span>
            </label>
            <input
              id="add-username"
              v-model="addForm.username"
              data-testid="add-username"
              type="text"
              aria-label="用户名"
              placeholder="用于登录的用户名"
              class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
            />
            <p
              v-if="addErrors.username"
              data-testid="add-error-username"
              class="mt-1 text-xs text-danger"
            >
              {{ addErrors.username }}
            </p>
          </div>
          <div>
            <label for="add-password" class="mb-1.5 block text-sm font-medium text-text">
              密码 <span class="text-danger">*</span>
            </label>
            <input
              id="add-password"
              v-model="addForm.password"
              data-testid="add-password"
              type="password"
              aria-label="初始密码"
              placeholder="至少 6 位"
              class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
            />
            <p v-if="addErrors.password" class="mt-1 text-xs text-danger">
              {{ addErrors.password }}
            </p>
          </div>
          <div>
            <label for="add-displayname" class="mb-1.5 block text-sm font-medium text-text">
              显示名 <span class="text-danger">*</span>
            </label>
            <input
              id="add-displayname"
              v-model="addForm.displayName"
              data-testid="add-displayname"
              type="text"
              aria-label="显示名"
              placeholder="展示在后台与对话中的名称"
              class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
            />
            <p v-if="addErrors.displayName" class="mt-1 text-xs text-danger">
              {{ addErrors.displayName }}
            </p>
          </div>
          <div class="flex justify-end gap-2 pt-2">
            <Button
              variant="outline"
              :disabled="addSubmitting"
              data-testid="cancel-add-user"
              @click="closeAdd"
              >取消</Button
            >
            <Button type="submit" data-testid="submit-add-user" :disabled="addSubmitting">
              <PhSpinnerGap v-if="addSubmitting" class="h-4 w-4 animate-spin" />
              {{ addSubmitting ? '创建中' : '创建' }}
            </Button>
          </div>
        </form>
      </div>
    </div>

    <!-- 编辑显示名 Dialog -->
    <div
      v-if="editing"
      data-testid="edit-dialog"
      class="fixed inset-0 z-50 flex items-center justify-center bg-overlay p-4"
      @keydown.esc="closeEdit"
      @click.self="closeEdit"
    >
      <div
        class="animate-menu-in w-full max-w-[440px] rounded-xl border border-border bg-surface p-6 shadow-lg"
        role="dialog"
        aria-modal="true"
        @click.stop
      >
        <h2 class="text-base font-semibold text-text">编辑显示名</h2>
        <div class="mt-5">
          <label for="edit-displayname" class="mb-1.5 block text-sm font-medium text-text">
            显示名 <span class="text-danger">*</span>
          </label>
          <input
            id="edit-displayname"
            v-model="editName"
            data-testid="edit-displayname"
            type="text"
            aria-label="显示名"
            class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 focus:border-brand focus:ring-2 focus:ring-brand/20"
          />
          <p v-if="editError" class="mt-1 text-xs text-danger">{{ editError }}</p>
        </div>
        <div class="mt-5 flex justify-end gap-2">
          <Button
            variant="outline"
            :disabled="editSubmitting"
            data-testid="cancel-edit"
            @click="closeEdit"
            >取消</Button
          >
          <Button data-testid="submit-edit" :disabled="editSubmitting" @click="submitEdit">
            <PhSpinnerGap v-if="editSubmitting" class="h-4 w-4 animate-spin" />
            {{ editSubmitting ? '保存中' : '保存' }}
          </Button>
        </div>
      </div>
    </div>

    <!-- 重置密码 Dialog -->
    <div
      v-if="resetting"
      data-testid="reset-dialog"
      class="fixed inset-0 z-50 flex items-center justify-center bg-overlay p-4"
      @keydown.esc="closeReset"
      @click.self="closeReset"
    >
      <div
        class="animate-menu-in w-full max-w-[440px] rounded-xl border border-border bg-surface p-6 shadow-lg"
        role="dialog"
        aria-modal="true"
        @click.stop
      >
        <h2 class="text-base font-semibold text-text">重置密码</h2>
        <p class="mt-2 text-sm text-text-muted">
          为「{{ resetting.displayName }}」设置新密码，重置后需使用新密码登录
        </p>
        <div class="mt-5 space-y-4">
          <div>
            <label for="reset-password" class="mb-1.5 block text-sm font-medium text-text">
              新密码 <span class="text-danger">*</span>
            </label>
            <input
              id="reset-password"
              v-model="newPassword"
              data-testid="reset-password"
              type="password"
              aria-label="新密码"
              placeholder="至少 6 位"
              class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
            />
          </div>
          <div>
            <label for="reset-confirm" class="mb-1.5 block text-sm font-medium text-text">
              确认新密码 <span class="text-danger">*</span>
            </label>
            <input
              id="reset-confirm"
              v-model="confirmPassword"
              data-testid="reset-confirm"
              type="password"
              aria-label="确认新密码"
              placeholder="再次输入新密码"
              class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
            />
          </div>
          <p v-if="resetError" data-testid="reset-error" class="text-xs text-danger">
            {{ resetError }}
          </p>
        </div>
        <div class="mt-5 flex justify-end gap-2">
          <Button
            variant="outline"
            :disabled="resetSubmitting"
            data-testid="cancel-reset"
            @click="closeReset"
            >取消</Button
          >
          <Button data-testid="submit-reset" :disabled="resetSubmitting" @click="submitReset">
            <PhSpinnerGap v-if="resetSubmitting" class="h-4 w-4 animate-spin" />
            {{ resetSubmitting ? '重置中' : '确认重置' }}
          </Button>
        </div>
      </div>
    </div>

    <!-- 禁用/启用二次确认 -->
    <div
      v-if="statusTarget"
      data-testid="status-dialog"
      class="fixed inset-0 z-50 flex items-center justify-center bg-overlay p-4"
      @keydown.esc="cancelStatusToggle"
      @click.self="cancelStatusToggle"
    >
      <div
        class="animate-menu-in w-full max-w-[440px] rounded-xl border border-border bg-surface p-6 shadow-lg"
        role="alertdialog"
        aria-modal="true"
        @click.stop
      >
        <div class="flex items-start gap-3">
          <div class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-danger/10">
            <PhWarningCircle class="h-5 w-5 text-danger" />
          </div>
          <div>
            <h2 class="text-base font-semibold text-text">
              {{ statusNext === 'DISABLED' ? '禁用教师' : '启用教师' }}
            </h2>
            <p class="mt-2 text-sm leading-relaxed text-text-muted">
              <template v-if="statusNext === 'DISABLED'">
                禁用后「{{ statusTarget.displayName }}」将无法登录课程助手，已登录设备会被
                后续登录校验拦截。确认禁用？
              </template>
              <template v-else>确认恢复「{{ statusTarget.displayName }}」的登录权限？</template>
            </p>
          </div>
        </div>
        <div class="mt-5 flex justify-end gap-2">
          <Button
            variant="outline"
            :disabled="statusSubmitting"
            data-testid="cancel-status"
            @click="cancelStatusToggle"
            >取消</Button
          >
          <Button
            :variant="statusNext === 'DISABLED' ? 'danger' : 'default'"
            data-testid="submit-status"
            :disabled="statusSubmitting"
            @click="confirmStatusToggle"
          >
            <PhSpinnerGap v-if="statusSubmitting" class="h-4 w-4 animate-spin" />
            {{ statusSubmitting ? '提交中' : '确认' }}
          </Button>
        </div>
      </div>
    </div>

    <!-- 删除教师二次确认 -->
    <div
      v-if="deleting"
      data-testid="user-del-dialog"
      class="fixed inset-0 z-50 flex items-center justify-center bg-overlay p-4"
      @keydown.esc="cancelDelete"
      @click.self="cancelDelete"
    >
      <div
        class="animate-menu-in w-full max-w-[440px] rounded-xl border border-border bg-surface p-6 shadow-lg"
        role="alertdialog"
        aria-modal="true"
        @click.stop
      >
        <div class="flex items-start gap-3">
          <div class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-danger/10">
            <PhWarningCircle class="h-5 w-5 text-danger" />
          </div>
          <div>
            <h2 class="text-base font-semibold text-text">删除教师</h2>
            <p class="mt-2 text-sm leading-relaxed text-text-muted">
              删除后「{{ deleting.displayName }}」的账号与登录权限一并移除，
              <span class="font-medium text-danger">此操作不可恢复</span>。确认删除？
            </p>
          </div>
        </div>
        <div class="mt-5 flex justify-end gap-2">
          <Button
            variant="outline"
            :disabled="deleteSubmitting"
            data-testid="cancel-user-del"
            @click="cancelDelete"
            >取消</Button
          >
          <Button
            variant="danger"
            data-testid="confirm-user-del"
            :disabled="deleteSubmitting"
            @click="confirmDelete"
          >
            <PhSpinnerGap v-if="deleteSubmitting" class="h-4 w-4 animate-spin" />
            {{ deleteSubmitting ? '删除中' : '确认删除' }}
          </Button>
        </div>
      </div>
    </div>
  </div>
</template>
