<script setup lang="ts">
/**
 * 学生管理（UI 重构 2026-08-25：从 UsersView 拆分，职责=学生账号域；
 * 2026-08-27 紫系换肤重制：PageHead/DataTable/EmptyState/ConfirmDialog 新设计系统组件）
 *
 * 能力：学生账号分页列表（用户名/显示名/状态/创建时间）+ 添加学生 + 编辑显示名 +
 * 重置密码（zod ≥6 + 两次一致）+ 禁用/启用（二次确认）+ 删除（二次确认）。
 * 角色固定 STUDENT：本页只管理学生，不再承载教师/账号混域（用户拍板剥离）。
 * 权限矩阵：自身行禁用/启用入口隐藏（防自锁，后端 A7 同时拒绝）。
 */
import { computed, reactive, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { z } from 'zod'
import { PhSpinnerGap, PhStudent, PhUserPlus } from '@phosphor-icons/vue'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { DataTable } from '@/components/ui/data-table'
import { EmptyState } from '@/components/ui/empty-state'
import { PageHead } from '@/components/ui/page-head'
import { vReveal } from '@/directives/reveal'
import { ApiError, userApi } from '@/lib/api'
import { showToast } from '@/lib/toast'
import { formatDateTime } from '@/lib/utils'
import { useAuthStore } from '@/stores/auth'

import type { UserDTO, UserStatus } from '@/lib/types'

/** 每页条数 */
const PAGE_SIZE = 10

const auth = useAuthStore()

/** 创建学生账号表单校验 schema：username/displayName 非空、password ≥6 位 */
const createSchema = z.object({
  username: z.string().trim().min(1, '请输入用户名'),
  password: z.string().min(1, '请输入密码').min(6, '密码至少 6 位'),
  displayName: z.string().trim().min(1, '请输入显示名'),
})

/** 页码：查询键组成之一，变化自动触发新查询 */
const page = ref(1)

/** 查询键：页码变化即重拉当前页（vue-query 数据源，C.1.4） */
const queryKey = computed(() => ['admin-students', page.value])

const {
  data,
  isLoading,
  isError,
  error: queryError,
  refetch,
} = useQuery({
  queryKey,
  queryFn: () => userApi.list({ page: page.value, size: PAGE_SIZE, role: 'STUDENT' }),
})

/** 列表行数据：total 为 Long 字符串铁律 */
const students = computed(() => data.value?.records ?? [])
const total = computed(() => data.value?.total ?? '0')

/** 总页数：total 为 Long 字符串，转 number 后按 PAGE_SIZE 上取整 */
const totalPages = computed(() => Math.max(1, Math.ceil(Number(total.value) / PAGE_SIZE)))

/** 列表加载失败横幅文案（queryError 非空时透出；503 统一降级） */
const listError = computed(() =>
  isError.value ? messageOf(queryError.value, '学生列表加载失败，请稍后重试') : '',
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
function refreshStudents() {
  queryClient.invalidateQueries({ queryKey: ['admin-students'] })
}

/** 自身行判定：当前登录用户（auth.userId）的行禁用/启用按钮隐藏（防自锁） */
function isSelf(u: UserDTO): boolean {
  return auth.userId === u.id
}

/** 状态 Badge：ACTIVE success / DISABLED danger */
function statusVariant(status: UserStatus) {
  return status === 'ACTIVE' ? ('success' as const) : ('danger' as const)
}

// ============ 添加学生 Dialog（角色固定 STUDENT，无角色选择器） ============

const addOpen = ref(false)
const addForm = reactive({ username: '', password: '', displayName: '' })
const addErrors = reactive({ username: '', password: '', displayName: '' })

/** 创建学生提交（isPending 驱动按钮禁用与拦截；成功后失效列表键） */
const { isPending: addSubmitting, mutate: submitAddMutation } = useMutation({
  mutationFn: (payload: { username: string; password: string; displayName: string }) =>
    userApi.create({ ...payload, role: 'STUDENT' }),
  onSuccess: () => {
    showToast('学生账号已创建', 'success')
    addOpen.value = false
    refreshStudents()
  },
  onError: (err) => {
    showToast(messageOf(err, '创建学生失败，请稍后重试'), 'danger')
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

/** 提交创建学生：zod 前置校验通过后走 mutation（role 恒 STUDENT） */
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
    refreshStudents()
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
    showToast(statusNext.value === 'DISABLED' ? '已禁用该学生' : '已启用该学生', 'success')
    statusTarget.value = null
    refreshStudents()
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

/** 删除学生提交（成功后失效列表键；删除末页最后一条会留空页——回退一页防空页） */
const { isPending: deleteSubmitting, mutate: deleteMutation } = useMutation({
  mutationFn: (id: string) => userApi.remove(id),
  onSuccess: () => {
    showToast('学生已删除', 'success')
    deleting.value = null
    if (students.value.length === 1 && page.value > 1) {
      page.value -= 1
    } else {
      queryClient.invalidateQueries({ queryKey: ['admin-students'] })
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
    <!-- 页头：主标题 + 副题 + 右侧「添加学生」主操作（设计稿 page-head 形态） -->
    <PageHead v-reveal title="学生管理" subtitle="管理学生账号、状态与密码">
      <template #actions>
        <Button data-testid="add-student" @click="openAdd">
          <PhUserPlus class="h-4 w-4" aria-hidden="true" />
          添加学生
        </Button>
      </template>
    </PageHead>

    <!-- 错误态：页内横幅 + 重试 -->
    <div
      v-if="listError"
      role="alert"
      class="mt-5 flex items-center justify-between gap-4 rounded-xl border border-danger/30 bg-danger/5 px-4 py-3"
    >
      <span class="text-sm text-danger">{{ listError }}</span>
      <Button variant="outline" size="sm" data-testid="retry-students" @click="refetch"
        >重试</Button
      >
    </div>

    <!-- 加载态：表格骨架屏 -->
    <div
      v-else-if="isLoading"
      data-testid="student-skeleton"
      class="mt-5 overflow-hidden rounded-2xl border border-border bg-surface shadow-xs"
      aria-label="学生列表加载中"
    >
      <div class="flex items-center gap-6 border-b border-border bg-surface-2 px-6 py-4">
        <div
          v-for="i in 5"
          :key="`head-${i}`"
          class="h-3 w-20 animate-pulse rounded bg-surface-2"
        />
      </div>
      <div
        v-for="i in 5"
        :key="`row-${i}`"
        class="h-12 animate-pulse border-b border-border bg-surface last:border-b-0"
      />
    </div>

    <!-- 空态：语义文案 + 添加入口 -->
    <div
      v-else-if="students.length === 0"
      v-reveal
      class="mt-5 rounded-2xl border border-border bg-surface shadow-xs"
    >
      <EmptyState title="还没有学生" description="创建学生账号后即可由学生登录使用课程助手">
        <template #icon>
          <PhStudent class="h-6 w-6" aria-hidden="true" />
        </template>
        <template #action>
          <Button data-testid="add-student-empty" @click="openAdd">添加学生</Button>
        </template>
      </EmptyState>
    </div>

    <!-- 正常态：分页表格（DataTable 壳：lav 圆角表头 + 行悬停 + 行级联入场） -->
    <template v-else>
      <div v-reveal class="overflow-hidden rounded-2xl border border-border bg-surface shadow-xs">
        <DataTable data-testid="student-table" label="学生列表">
          <template #header>
            <tr>
              <th class="w-[24%]">用户名</th>
              <th>显示名</th>
              <th class="w-32">状态</th>
              <th class="w-48">创建时间</th>
              <th class="w-80">操作</th>
            </tr>
          </template>
          <tr v-for="u in students" :key="u.id" :data-testid="`row-${u.id}`">
            <!-- 主标识列：深色半粗（设计稿 .course-name 主列强调，其余列 muted） -->
            <td>
              <span class="font-semibold text-text">{{ u.username }}</span>
            </td>
            <td>{{ u.displayName }}</td>
            <td>
              <Badge :data-testid="`user-status-${u.id}`" :variant="statusVariant(u.status)">
                {{ u.status }}
              </Badge>
            </td>
            <td class="tabular-nums">{{ formatDateTime(u.createdAt) }}</td>
            <td>
              <div class="flex items-center gap-1">
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
        </DataTable>
      </div>

      <!-- 分页器：左「共 N 条」右 上/下页 + 页码 -->
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

    <!-- 添加学生 Dialog（角色固定 STUDENT，无角色选择器；表单 Dialog 保留内联实现承载 zod 校验） -->
    <div
      v-if="addOpen"
      data-testid="add-user-dialog"
      class="fixed inset-0 z-50 flex animate-fade-in items-center justify-center bg-overlay p-4"
      @keydown.esc="closeAdd"
      @click.self="closeAdd"
    >
      <div
        class="animate-menu-in w-full max-w-[480px] rounded-2xl bg-surface p-6 shadow-lg"
        role="dialog"
        aria-modal="true"
        aria-label="添加学生"
        @click.stop
      >
        <h2 class="text-lg font-bold text-text">添加学生</h2>
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
              class="h-10 w-full rounded-xl border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
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
              class="h-10 w-full rounded-xl border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
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
              class="h-10 w-full rounded-xl border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
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
      class="fixed inset-0 z-50 flex animate-fade-in items-center justify-center bg-overlay p-4"
      @keydown.esc="closeEdit"
      @click.self="closeEdit"
    >
      <div
        class="animate-menu-in w-full max-w-[440px] rounded-2xl bg-surface p-6 shadow-lg"
        role="dialog"
        aria-modal="true"
        aria-label="编辑显示名"
        @click.stop
      >
        <h2 class="text-lg font-bold text-text">编辑显示名</h2>
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
            class="h-10 w-full rounded-xl border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 focus:border-brand focus:ring-2 focus:ring-brand/20"
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
      class="fixed inset-0 z-50 flex animate-fade-in items-center justify-center bg-overlay p-4"
      @keydown.esc="closeReset"
      @click.self="closeReset"
    >
      <div
        class="animate-menu-in w-full max-w-[440px] rounded-2xl bg-surface p-6 shadow-lg"
        role="dialog"
        aria-modal="true"
        aria-label="重置密码"
        @click.stop
      >
        <h2 class="text-lg font-bold text-text">重置密码</h2>
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
              class="h-10 w-full rounded-xl border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
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
              class="h-10 w-full rounded-xl border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
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

    <!-- 禁用/启用二次确认（ConfirmDialog 标准壳：$attrs 转发保住 submit-status 选择器契约；
         外层 v-if 包装 div 承载 status-dialog 选择器，contents 不参与布局） -->
    <div v-if="statusTarget" data-testid="status-dialog" class="contents">
      <ConfirmDialog
        :open="true"
        :title="statusNext === 'DISABLED' ? '禁用学生' : '启用学生'"
        :description="
          statusNext === 'DISABLED'
            ? `禁用后「${statusTarget.displayName}」将无法登录课程助手，已登录设备会被后续登录校验拦截。确认禁用？`
            : `确认恢复「${statusTarget.displayName}」的登录权限？`
        "
        confirm-text="确认"
        :tone="statusNext === 'DISABLED' ? 'danger' : 'brand'"
        :loading="statusSubmitting"
        data-testid="submit-status"
        @confirm="confirmStatusToggle"
        @cancel="cancelStatusToggle"
      />
    </div>

    <!-- 删除学生二次确认（ConfirmDialog：confirm-user-del 选择器契约同上） -->
    <div v-if="deleting" data-testid="user-del-dialog" class="contents">
      <ConfirmDialog
        :open="true"
        title="删除学生"
        :description="`删除后「${deleting.displayName}」的账号与登录权限一并移除，此操作不可恢复。确认删除？`"
        confirm-text="确认删除"
        tone="danger"
        :loading="deleteSubmitting"
        data-testid="confirm-user-del"
        @confirm="confirmDelete"
        @cancel="cancelDelete"
      />
    </div>
  </div>
</template>
