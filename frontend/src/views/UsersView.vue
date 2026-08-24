<script setup lang="ts">
/**
 * 用户管理页（设计 §2.4.5）
 *
 * 能力清单：
 * 1. 角色 Tab：全部 / 教师 / 学生（点击切换携带 role 参数，计数来自列表 total）
 * 2. 添加学生（两角色入口，教师端固定角色 STUDENT 无选择器）/
 *    添加教师（仅超管，角色选择器含 TEACHER）
 * 3. 表格：用户名 / 显示名 / 角色 Badge / 状态（ACTIVE emerald / DISABLED red）/
 *    创建时间 / 操作（编辑 displayName、重置密码、禁用/启用、删除）
 * 4. 权限矩阵：当前登录超管自身行禁用/启用按钮隐藏（useAuthStore.userId 比对，
 *    防自锁）；后端同时拒绝超管自身禁用（A7）
 * 5. 重置密码 Dialog：zod ≥6 前置校验 + 两次输入一致 → resetPassword({newPassword})
 * 6. 禁用/启用：二次确认（danger 实底 + submitting 拦截）
 * 7. 删除：二次确认（danger，不可恢复）
 * 8. 四态：loading 骨架 / empty 含添加入口 / error 横幅重试 / 正常
 *
 * 契约要点：id/total 为 Long 字符串铁律；时间 ISO-8601 短格式；
 * 教师限己建学生由后端过滤器约束（P2-2），前端不额外过滤。
 *
 * 线程安全注意：全部状态为组件私有 ref，无跨实例共享可变状态。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { z } from 'zod'
import { PhPlus, PhSpinnerGap, PhUserPlus, PhWarningCircle } from '@phosphor-icons/vue'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ApiError, userApi } from '@/lib/api'
import { showToast } from '@/lib/toast'
import { formatDateTime } from '@/lib/utils'
import { useAuthStore } from '@/stores/auth'

import type { UserDTO, UserRole, UserStatus } from '@/lib/types'

/** 每页条数（设计 §2.6 分页器） */
const PAGE_SIZE = 10

/** 角色 Tab 定义：value 为接口 role 参数（ALL 不携带） */
const TABS = [
  { key: 'ALL', label: '全部' },
  { key: 'TEACHER', label: '教师' },
  { key: 'STUDENT', label: '学生' },
] as const

type RoleTab = (typeof TABS)[number]['key']

const auth = useAuthStore()

/** 超管判定：控制「添加教师」入口与角色选择器可见性（设计 §2.4.5） */
const isSuperAdmin = computed(() => auth.role === 'SUPER_ADMIN')

// ====================================================================
// 列表数据（角色 Tab + 分页，四态页面级收敛）
// ====================================================================

const users = ref<UserDTO[]>([])
const loading = ref(true)
const error = ref('')
const activeTab = ref<RoleTab>('ALL')
const page = ref(1)
const total = ref('0')

/** 总页数：total 为 Long 字符串，转 number 后按 PAGE_SIZE 上取整 */
const totalPages = computed(() => Math.max(1, Math.ceil(Number(total.value) / PAGE_SIZE)))

/**
 * 拉取当前 Tab 用户列表（分页 + role 参数）
 *
 * 边界：删除/筛选导致末页清空时回退一页重拉（防空页停留）。
 */
async function load() {
  loading.value = true
  error.value = ''
  try {
    // 角色 Tab：ALL 不携带 role（后端全量），教师/学生携带对应枚举
    const res = await userApi.list({
      page: page.value,
      size: PAGE_SIZE,
      ...(activeTab.value === 'ALL' ? {} : { role: activeTab.value as UserRole }),
    })
    users.value = res.records ?? []
    total.value = res.total
    if (users.value.length === 0 && page.value > 1) {
      page.value -= 1
      await load()
    }
  } catch (err) {
    error.value = messageOf(err, '用户列表加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

onMounted(load)

/** 接口错误分级文案（ApiError 透出 message，503 统一降级；非 ApiError 兜底） */
function messageOf(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    return err.code === 503 ? '服务暂时不可用，请稍后重试' : err.message
  }
  return fallback
}

/** 切换角色 Tab：重置第 1 页并重新拉取（计数 chip 取该次列表 total） */
function switchTab(key: RoleTab) {
  if (activeTab.value === key) return
  activeTab.value = key
  page.value = 1
  load()
}

/** 翻页：越界保护（禁用态按钮兜底） */
function changePage(next: number) {
  if (next < 1 || next > totalPages.value) return
  page.value = next
  load()
}

// ====================================================================
// 权限矩阵：自身禁用隐藏
// ====================================================================

/**
 * 自身行判定：当前登录用户（auth.userId）的行禁用/启用按钮隐藏
 *
 * 防超管/教师误禁自己导致账号锁死（后端 A7 同样拒绝，前端先行隐藏入口）。
 *
 * @param u 表格行用户
 * @returns 该行是否为当前登录用户自身
 */
function isSelf(u: UserDTO): boolean {
  return auth.userId === u.id
}

/** 角色 Badge 变体：TEACHER brand / SUPER_ADMIN info / STUDENT 中性（设计 §2.4.5 角色区分） */
function roleVariant(role: UserRole) {
  switch (role) {
    case 'TEACHER':
      return 'brand' as const
    case 'SUPER_ADMIN':
      return 'info' as const
    default:
      return 'default' as const
  }
}

/** 状态 Badge：ACTIVE emerald / DISABLED red（设计 §2.5 用户双色互斥） */
function statusVariant(status: UserStatus) {
  return status === 'ACTIVE' ? ('success' as const) : ('danger' as const)
}

// ====================================================================
// 添加用户 Dialog（教师固定 STUDENT 无选择器 / 超管含角色选择器）
// ====================================================================

/** 创建用户表单校验 schema：username/displayName 非空、password 非空且 ≥6 位 */
const createSchema = z.object({
  username: z.string().trim().min(1, '请输入用户名'),
  // 两级校验：空值提示「请输入密码」，短于 6 位提示长度要求
  password: z.string().min(1, '请输入密码').min(6, '密码至少 6 位'),
  displayName: z.string().trim().min(1, '请输入显示名'),
})

const addOpen = ref(false)
const addSubmitting = ref(false)
const addForm = reactive({
  username: '',
  password: '',
  displayName: '',
  role: 'STUDENT' as UserRole,
})
const addErrors = reactive({ username: '', password: '', displayName: '' })

function openAddStudent() {
  addForm.role = 'STUDENT'
  addOpen.value = true
  resetAddErrors()
}

function openAddTeacher() {
  addForm.role = 'TEACHER'
  addOpen.value = true
  resetAddErrors()
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

/**
 * 提交创建用户：zod 前置校验（失败就地报错不发请求）→ create → toast → 刷新
 *
 * 教师端 addForm.role 恒 STUDENT（角色选择器隐藏）；超管端由选择器写入。
 */
async function submitAdd() {
  const parsed = createSchema.safeParse(addForm)
  if (!parsed.success) {
    // zod v4 issues.path 为字段路径数组：includes 判定字段归属，就地分列报错
    const issues = parsed.error.issues
    addErrors.username = issues.find((i) => i.path.includes('username'))?.message ?? ''
    addErrors.password = issues.find((i) => i.path.includes('password'))?.message ?? ''
    addErrors.displayName = issues.find((i) => i.path.includes('displayName'))?.message ?? ''
    return
  }
  addErrors.username = ''
  addErrors.password = ''
  addErrors.displayName = ''
  addSubmitting.value = true
  try {
    await userApi.create({
      username: addForm.username.trim(),
      password: addForm.password,
      displayName: addForm.displayName.trim(),
      role: addForm.role,
    })
    showToast(addForm.role === 'STUDENT' ? '学生账号已创建' : '教师账号已创建', 'success')
    addOpen.value = false
    await load()
  } catch (err) {
    showToast(messageOf(err, '创建用户失败，请稍后重试'), 'danger')
  } finally {
    addSubmitting.value = false
  }
}

// ====================================================================
// 编辑 displayName Dialog
// ====================================================================

const editing = ref<UserDTO | null>(null)
const editName = ref('')
const editError = ref('')
const editSubmitting = ref(false)

function openEdit(u: UserDTO) {
  editing.value = u
  editName.value = u.displayName
  editError.value = ''
}

function closeEdit() {
  if (editSubmitting.value) return
  editing.value = null
}

/** 保存显示名：必填校验 → update({displayName}) → toast → 刷新 */
async function submitEdit() {
  if (!editing.value) return
  const name = editName.value.trim()
  if (!name) {
    editError.value = '请输入显示名'
    return
  }
  editSubmitting.value = true
  try {
    await userApi.update(editing.value.id, { displayName: name })
    showToast('显示名已更新', 'success')
    editing.value = null
    await load()
  } catch (err) {
    showToast(messageOf(err, '保存失败，请稍后重试'), 'danger')
  } finally {
    editSubmitting.value = false
  }
}

// ====================================================================
// 重置密码 Dialog（zod ≥6 + 两次输入一致）
// ====================================================================

const resetSchema = z.object({
  newPassword: z.string().min(6, '新密码至少 6 位'),
})

const resetting = ref<UserDTO | null>(null)
const newPassword = ref('')
const confirmPassword = ref('')
const resetError = ref('')
const resetSubmitting = ref(false)

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

/**
 * 提交重置密码：zod ≥6 前置校验 → 两次输入一致校验 → resetPassword({newPassword})
 *
 * 校验失败就地报错不发请求；成功重置后学生需用新密码重新登录。
 */
async function submitReset() {
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
  resetSubmitting.value = true
  try {
    await userApi.resetPassword(resetting.value.id, { newPassword: newPassword.value })
    showToast('密码已重置', 'success')
    resetting.value = null
  } catch (err) {
    showToast(messageOf(err, '重置密码失败，请稍后重试'), 'danger')
  } finally {
    resetSubmitting.value = false
  }
}

// ====================================================================
// 禁用/启用（二次确认，danger）
// ====================================================================

const statusTarget = ref<UserDTO | null>(null)
const statusNext = ref<UserStatus>('DISABLED')
const statusSubmitting = ref(false)

function requestStatusToggle(u: UserDTO) {
  statusTarget.value = u
  statusNext.value = u.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
}

function cancelStatusToggle() {
  if (statusSubmitting.value) return
  statusTarget.value = null
}

/** 确认禁用/启用：updateStatus({status}) → toast → 刷新（danger 实底 + 不可恢复告警） */
async function confirmStatusToggle() {
  if (!statusTarget.value) return
  statusSubmitting.value = true
  try {
    await userApi.updateStatus(statusTarget.value.id, { status: statusNext.value })
    showToast(statusNext.value === 'DISABLED' ? '已禁用该用户' : '已启用该用户', 'success')
    statusTarget.value = null
    await load()
  } catch (err) {
    showToast(messageOf(err, '操作失败，请稍后重试'), 'danger')
  } finally {
    statusSubmitting.value = false
  }
}

// ====================================================================
// 删除（二次确认，不可恢复）
// ====================================================================

const deleting = ref<UserDTO | null>(null)
const deleteSubmitting = ref(false)

function requestDelete(u: UserDTO) {
  deleting.value = u
}

function cancelDelete() {
  if (deleteSubmitting.value) return
  deleting.value = null
}

/** 确认删除：remove → toast → 刷新（教师仅可删自己创建的学生，后端约束） */
async function confirmDelete() {
  if (!deleting.value) return
  deleteSubmitting.value = true
  try {
    await userApi.remove(deleting.value.id)
    showToast('用户已删除', 'success')
    deleting.value = null
    await load()
  } catch (err) {
    showToast(messageOf(err, '删除失败，请稍后重试'), 'danger')
  } finally {
    deleteSubmitting.value = false
  }
}

/** 当前 Tab 的空态文案与添加入口角色差异 */
const emptyText = computed(() => {
  switch (activeTab.value) {
    case 'TEACHER':
      return '还没有教师'
    case 'STUDENT':
      return '还没有学生'
    default:
      return '还没有用户'
  }
})
</script>

<template>
  <main class="mx-auto max-w-[1400px] px-8 py-6">
    <!-- 页头操作行：添加学生常驻；添加教师仅超管（设计 §2.4.5 权限矩阵） -->
    <div class="mb-4 flex items-center justify-between">
      <p class="text-sm text-text-muted">管理账号角色、状态与密码，教师仅可操作自己创建的学生</p>
      <div class="flex items-center gap-2">
        <Button data-testid="add-student" @click="openAddStudent">
          <PhUserPlus class="h-4 w-4" />
          添加学生
        </Button>
        <Button
          v-if="isSuperAdmin"
          data-testid="add-teacher"
          variant="outline"
          @click="openAddTeacher"
        >
          <PhPlus class="h-4 w-4" />
          添加教师
        </Button>
      </div>
    </div>

    <!-- 角色 Tab：计数 chip 仅激活 Tab 展示（取自列表 total） -->
    <div class="mb-4 inline-flex items-center gap-1 rounded-lg border border-border bg-surface p-1">
      <button
        v-for="t in TABS"
        :key="t.key"
        type="button"
        :data-testid="`tab-${t.key.toLowerCase()}`"
        class="inline-flex items-center gap-1.5 rounded-md px-4 py-1.5 text-sm transition-colors duration-150 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand"
        :class="
          activeTab === t.key
            ? 'bg-brand-soft font-medium text-brand-strong'
            : 'text-text-muted hover:bg-surface-2 hover:text-text'
        "
        @click="switchTab(t.key)"
      >
        {{ t.label }}
        <span
          v-if="activeTab === t.key"
          :data-testid="`tab-count-${t.key.toLowerCase()}`"
          class="rounded-full bg-surface px-1.5 text-xs tabular-nums text-text"
        >
          {{ total }}
        </span>
      </button>
    </div>

    <!-- 错误态：页内横幅 + 重试（设计 §1.7） -->
    <div
      v-if="error"
      role="alert"
      class="flex items-center justify-between gap-4 rounded-lg border border-danger/30 bg-red-50 px-4 py-3"
    >
      <span class="text-sm text-danger">{{ error }}</span>
      <Button variant="outline" size="sm" data-testid="retry-users" @click="load">重试</Button>
    </div>

    <!-- 加载态：表格骨架屏（表头 + 5 行灰条，与最终表格同形） -->
    <div
      v-else-if="loading"
      data-testid="user-skeleton"
      class="overflow-hidden rounded-xl border border-border bg-surface"
      aria-label="用户列表加载中"
    >
      <div class="flex items-center gap-6 border-b border-border bg-surface-2 px-4 py-2.5">
        <div
          v-for="i in 6"
          :key="`head-${i}`"
          class="h-3 w-20 animate-pulse rounded bg-slate-200"
        />
      </div>
      <div
        v-for="i in 5"
        :key="`row-${i}`"
        class="h-11 animate-pulse border-b border-border bg-slate-50"
      />
    </div>

    <!-- 空态：按 Tab 语义文案 + 添加入口（禁裸「暂无数据」） -->
    <div
      v-else-if="users.length === 0"
      class="flex flex-col items-center justify-center rounded-xl border border-dashed border-border bg-surface py-14 text-center"
    >
      <PhWarningCircle class="h-8 w-8 text-text-subtle" />
      <p class="mt-3 text-sm font-medium text-text">{{ emptyText }}</p>
      <p class="mt-1 text-xs text-text-muted">创建账号后即可由学生登录使用课程助手</p>
      <div class="mt-4 flex gap-2">
        <Button data-testid="add-student-empty" @click="openAddStudent">添加学生</Button>
        <Button
          v-if="isSuperAdmin"
          data-testid="add-teacher-empty"
          variant="outline"
          @click="openAddTeacher"
        >
          添加教师
        </Button>
      </div>
    </div>

    <!-- 正常态：分页表格（用户名/显示名/角色/状态/创建时间/操作） -->
    <template v-else>
      <div class="overflow-hidden rounded-xl border border-border bg-surface">
        <table data-testid="user-table" class="w-full text-sm">
          <thead class="border-b border-border bg-surface-2 text-left text-xs text-text-muted">
            <tr>
              <th class="px-4 py-2.5 font-medium">用户名</th>
              <th class="px-4 py-2.5 font-medium">显示名</th>
              <th class="w-28 px-4 py-2.5 font-medium">角色</th>
              <th class="w-28 px-4 py-2.5 font-medium">状态</th>
              <th class="w-32 px-4 py-2.5 font-medium">创建时间</th>
              <th class="w-72 px-4 py-2.5 text-right font-medium">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="u in users"
              :key="u.id"
              :data-testid="`row-${u.id}`"
              class="h-11 border-b border-border last:border-b-0 transition-colors duration-150 hover:bg-surface-2"
            >
              <td class="px-4 font-medium text-text">{{ u.username }}</td>
              <td class="px-4 text-text-muted">{{ u.displayName }}</td>
              <td class="px-4">
                <Badge :variant="roleVariant(u.role)">{{ u.role }}</Badge>
              </td>
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
                  <!-- 权限矩阵：自身行禁用/启用按钮隐藏（防自锁） -->
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
         添加用户 Dialog（教师端无角色选择器，固定 STUDENT；超管含角色选择器）
         ================================================================ -->
    <div
      v-if="addOpen"
      data-testid="add-user-dialog"
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
        <h2 class="text-base font-semibold text-text">
          {{ addForm.role === 'STUDENT' ? '添加学生' : '添加教师' }}
        </h2>
        <form data-testid="add-form" class="mt-5 space-y-4" novalidate @submit.prevent="submitAdd">
          <!-- 角色选择器：仅超管展示（设计 §2.4.5 权限矩阵），教师固定 STUDENT -->
          <div v-if="isSuperAdmin">
            <label for="add-role" class="mb-1.5 block text-sm font-medium text-text">
              角色 <span class="text-danger">*</span>
            </label>
            <select
              id="add-role"
              v-model="addForm.role"
              data-testid="add-role"
              class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 focus:border-brand focus:ring-2 focus:ring-brand/20"
            >
              <option value="STUDENT">STUDENT（学生）</option>
              <option value="TEACHER">TEACHER（教师）</option>
            </select>
          </div>
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
      class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4"
      @keydown.esc="closeEdit"
      @click.self="closeEdit"
    >
      <div
        class="w-full max-w-[440px] rounded-xl border border-border bg-surface p-6 shadow-md"
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

    <!-- 重置密码 Dialog（zod ≥6 + 两次输入一致） -->
    <div
      v-if="resetting"
      data-testid="reset-dialog"
      class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4"
      @keydown.esc="closeReset"
      @click.self="closeReset"
    >
      <div
        class="w-full max-w-[440px] rounded-xl border border-border bg-surface p-6 shadow-md"
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

    <!-- 禁用/启用二次确认（danger 实底 + submitting 拦截） -->
    <div
      v-if="statusTarget"
      data-testid="status-dialog"
      class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4"
      @keydown.esc="cancelStatusToggle"
      @click.self="cancelStatusToggle"
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
            <h2 class="text-base font-semibold text-text">
              {{ statusNext === 'DISABLED' ? '禁用用户' : '启用用户' }}
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
          >
            取消
          </Button>
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

    <!-- 删除用户二次确认（不可恢复告警，设计 §2.6） -->
    <div
      v-if="deleting"
      data-testid="user-del-dialog"
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
            <h2 class="text-base font-semibold text-text">删除用户</h2>
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
  </main>
</template>
