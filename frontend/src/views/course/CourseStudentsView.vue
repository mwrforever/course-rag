<script setup lang="ts">
/**
 * 课程学生名单（UI 重构 2026-08-25 从 CourseEditView 拆出；2026-08-27 紫系重制：
 * DataTable 表壳 + EmptyState + 移除确认迁移 ConfirmDialog）
 *
 * 职责：已选列表（username/displayName/enrolledAt）+ 添加 Dialog
 * （搜索多选 → POST 返回成功数 → 「成功添加 N 名」提示）+ 行移除二次确认。
 * 学生候选走 userApi role=STUDENT 一次拉取，搜索客户端过滤（R18）。
 */
import { computed, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { useRoute } from 'vue-router'
import {
  PhCheck,
  PhMagnifyingGlass,
  PhSpinnerGap,
  PhTrash,
  PhUserPlus,
  PhUsersThree,
} from '@phosphor-icons/vue'

import { Button } from '@/components/ui/button'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { DataTable } from '@/components/ui/data-table'
import { EmptyState } from '@/components/ui/empty-state'
import { ApiError, enrollmentApi, userApi } from '@/lib/api'
import { showToast } from '@/lib/toast'
import { formatDateTime } from '@/lib/utils'
import type { StudentDTO, UserDTO } from '@/lib/types'

const route = useRoute()
/** 课程 id（Long 字符串铁律） */
const courseId = computed(() => String(route.params.id ?? ''))

/** 学生名单（查询键含路由 id，换课程自动重拉） */
const {
  data: studentsData,
  isLoading,
  isError,
  error: queryError,
  refetch,
} = useQuery({
  queryKey: computed(() => ['course-students', courseId.value]),
  queryFn: async () => {
    try {
      return (await enrollmentApi.students(courseId.value)) ?? []
    } catch (err) {
      showToast(messageOf(err, '学生名单加载失败，请稍后重试'), 'danger')
      throw err
    }
  },
  // toast 语义在 queryFn 内：关闭重试与窗口聚焦重拉，避免失败提示叠加
  retry: false,
  refetchOnWindowFocus: false,
})

/** 学生名单行数据 */
const students = computed(() => studentsData.value ?? [])

/** 学生名单加载失败横幅文案（queryError 非空时透出；503 统一降级） */
const listError = computed(() =>
  isError.value ? messageOf(queryError.value, '学生名单加载失败，请稍后重试') : '',
)

/** 已报名学生 id 集合（候选过滤剔除已报名） */
const enrolledIds = computed(() => new Set(students.value.map((s) => s.id)))

const queryClient = useQueryClient()

/** 写操作成功后的名单刷新：按查询键失效重拉 */
function refreshStudents() {
  queryClient.invalidateQueries({ queryKey: ['course-students'] })
}

const studentDialogOpen = ref(false)
/** 学生候选池（Dialog 打开时按需拉取；失败仅 toast 不阻塞交互） */
const { data: studentCandidatesData, isLoading: studentCandidatesLoading } = useQuery({
  queryKey: ['student-candidates'],
  queryFn: async (): Promise<UserDTO[]> => {
    try {
      const res = await userApi.list({ role: 'STUDENT', size: 100 })
      return (res.records ?? []).filter((u) => u.role === 'STUDENT')
    } catch (err) {
      showToast(messageOf(err, '学生列表加载失败，请稍后重试'), 'danger')
      return []
    }
  },
  enabled: studentDialogOpen,
  retry: false,
  refetchOnWindowFocus: false,
})
const studentCandidates = computed(() => studentCandidatesData.value ?? [])
const studentSearch = ref('')
const studentSelected = ref<string[]>([])
/** 待移除学生：非 null 时展示二次确认 Dialog */
const studentDeleting = ref<StudentDTO | null>(null)

function messageOf(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    return err.code === 503 ? '服务暂时不可用，请稍后重试' : err.message
  }
  return fallback
}

/** 打开添加学生 Dialog：清空勾选与搜索（候选池由 enabled 翻转自动拉取） */
function openStudentDialog() {
  studentDialogOpen.value = true
  studentSearch.value = ''
  studentSelected.value = []
}

/** 学生候选过滤：角色 STUDENT 兜底 ＋ 剔除已报名 ＋ 搜索关键词（displayName/username） */
const studentOptions = computed(() => {
  const kw = studentSearch.value.trim()
  return studentCandidates.value.filter(
    (u) =>
      !enrolledIds.value.has(u.id) &&
      (kw === '' || u.displayName.includes(kw) || u.username.includes(kw)),
  )
})

/** 候选行点击切换勾选（多选追加，再次点击取消） */
function toggleStudent(u: UserDTO) {
  if (studentSelected.value.includes(u.id)) {
    studentSelected.value = studentSelected.value.filter((id) => id !== u.id)
  } else {
    studentSelected.value.push(u.id)
  }
}

/** 关闭添加学生 Dialog：提交期间拦截（防误关丢提交态） */
function closeStudentDialog() {
  if (studentSubmitting.value) return
  studentDialogOpen.value = false
}

/** 提交批量添加（POST /{id}/students {studentIds} → 以返回成功数提示） */
const { isPending: studentSubmitting, mutate: submitStudentsMutation } = useMutation({
  mutationFn: (ids: string[]) => enrollmentApi.addStudents(courseId.value, { studentIds: ids }),
  onSuccess: async (added) => {
    showToast(`成功添加 ${added} 名`, 'success')
    studentDialogOpen.value = false
    refreshStudents()
  },
  onError: (err) => {
    showToast(messageOf(err, '添加学生失败，请稍后重试'), 'danger')
  },
})

/** 提交批量添加：勾选非空校验 → 走 mutation */
function submitStudents() {
  if (studentSelected.value.length === 0) return
  submitStudentsMutation(studentSelected.value)
}

/** 打开移除学生二次确认 */
function requestDeleteStudent(s: StudentDTO) {
  studentDeleting.value = s
}

/** 取消移除确认（提交期间拦截） */
function cancelDeleteStudent() {
  if (studentDeletingLoading.value) return
  studentDeleting.value = null
}

/**
 * 移除确认弹窗开合回抛（v-model 语义）
 *
 * @param open ConfirmDialog 回抛的开合值：仅 false 有意义（关闭请求经 cancelDeleteStudent 拦截提交期）
 */
function onDelDialogOpen(open: boolean) {
  if (!open) cancelDeleteStudent()
}

/** 移除学生提交（成功后失效名单键） */
const { isPending: studentDeletingLoading, mutate: confirmDeleteStudentMutation } = useMutation({
  mutationFn: (id: string) => enrollmentApi.removeStudent(courseId.value, id),
  onSuccess: () => {
    showToast('已移除学生', 'success')
    studentDeleting.value = null
    refreshStudents()
  },
  onError: (err) => {
    showToast(messageOf(err, '移除学生失败，请稍后重试'), 'danger')
  },
})

/** 确认移除学生：提交中禁用按钮，完成/失败由 mutation 回调处理 */
function confirmDeleteStudent() {
  if (!studentDeleting.value) return
  confirmDeleteStudentMutation(studentDeleting.value.id)
}
</script>

<template>
  <section v-reveal class="rounded-2xl border border-border bg-surface shadow-xs">
    <div class="flex flex-wrap items-center justify-between gap-3 px-6 py-[18px]">
      <h2 class="text-lg font-extrabold tracking-tight text-text">
        学生名单
        <span class="ml-2 text-sm font-normal text-text-muted">共 {{ students.length }} 名</span>
      </h2>
      <Button size="sm" data-testid="add-students" @click="openStudentDialog">
        <PhUserPlus class="h-4 w-4" />
        添加学生
      </Button>
    </div>

    <!-- 加载骨架 -->
    <div v-if="isLoading" data-testid="students-skeleton" class="animate-pulse space-y-2 px-6 pb-6">
      <div v-for="i in 4" :key="i" class="h-12 rounded-xl bg-surface-2" />
    </div>

    <!-- 加载错误：横幅 + 重试 -->
    <div
      v-else-if="listError"
      role="alert"
      class="flex items-center justify-between gap-4 px-6 pb-6"
    >
      <span class="text-sm text-danger">{{ listError }}</span>
      <Button variant="outline" size="sm" @click="refetch">重试</Button>
    </div>

    <!-- 学生空态 -->
    <EmptyState
      v-else-if="students.length === 0"
      title="还没有学生报名"
      description="点击右上角「添加学生」开通名额"
    >
      <template #icon>
        <PhUsersThree class="h-6 w-6" aria-hidden="true" />
      </template>
    </EmptyState>

    <!-- 学生表格：username / displayName / enrolledAt + 移除 -->
    <DataTable v-else data-testid="student-table" label="学生名单" class="pb-2">
      <template #header>
        <tr>
          <th>用户名</th>
          <th>显示名</th>
          <th>报名时间</th>
          <th class="w-28 text-right">操作</th>
        </tr>
      </template>
      <tr v-for="s in students" :key="s.id" :data-testid="`student-row-${s.id}`">
        <td class="font-semibold text-text">{{ s.username }}</td>
        <td>{{ s.displayName }}</td>
        <td>
          <span class="tabular-nums">{{ formatDateTime(s.enrolledAt) }}</span>
        </td>
        <td class="text-right">
          <Button
            variant="ghost"
            size="sm"
            class="text-danger hover:bg-danger/5"
            :data-testid="`student-remove-${s.id}`"
            @click="requestDeleteStudent(s)"
          >
            <PhTrash class="h-3.5 w-3.5" />
            移除
          </Button>
        </td>
      </tr>
    </DataTable>

    <!-- 添加学生 Dialog（搜索多选；提交期 Esc/遮罩/取消全拦截） -->
    <div
      v-if="studentDialogOpen"
      data-testid="student-dialog"
      class="fixed inset-0 z-50 flex items-center justify-center bg-overlay p-4"
      @keydown.esc="closeStudentDialog"
      @click.self="closeStudentDialog"
    >
      <div
        class="animate-menu-in flex max-h-[560px] w-full max-w-[480px] flex-col rounded-2xl border border-border bg-surface p-6 shadow-lg"
        role="dialog"
        aria-modal="true"
      >
        <h2 class="text-lg font-extrabold tracking-tight text-text">添加学生</h2>
        <div class="relative mt-4">
          <PhMagnifyingGlass
            class="pointer-events-none absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2 text-text-subtle"
          />
          <input
            v-model="studentSearch"
            type="text"
            data-testid="student-search"
            aria-label="搜索学生"
            placeholder="搜索学生（显示名/用户名）"
            class="h-10 w-full rounded-xl border border-border bg-surface pr-3 pl-9 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
          />
        </div>
        <!-- 候选列表：整行点击切换多选（已报名学生自动剔除） -->
        <div class="mt-3 min-h-0 flex-1 space-y-1.5 overflow-y-auto border-y border-border py-3">
          <div v-if="studentCandidatesLoading" class="space-y-1.5">
            <div
              v-for="i in 4"
              :key="`cand-${i}`"
              class="h-10 animate-pulse rounded bg-surface-2"
            />
          </div>
          <button
            v-for="u in studentOptions"
            :key="u.id"
            type="button"
            :data-testid="`student-option-${u.id}`"
            class="flex w-full cursor-pointer items-center justify-between gap-2 rounded-[10px] border px-3 py-2 text-left transition-colors duration-150 hover:bg-surface-2"
            :class="
              studentSelected.includes(u.id) ? 'border-brand bg-brand-soft/50' : 'border-border'
            "
            @click="toggleStudent(u)"
          >
            <span class="min-w-0 truncate text-sm font-medium text-text">{{ u.displayName }}</span>
            <span class="shrink-0 truncate text-xs text-text-subtle">{{ u.username }}</span>
            <!-- 勾选态指示（选中 brand 实心勾，未选空心框） -->
            <span
              class="flex h-4 w-4 shrink-0 items-center justify-center rounded-sm border"
              :class="
                studentSelected.includes(u.id)
                  ? 'border-brand bg-brand text-white'
                  : 'border-border bg-surface'
              "
            >
              <PhCheck v-if="studentSelected.includes(u.id)" class="h-3 w-3" weight="bold" />
            </span>
          </button>
          <div
            v-if="!studentCandidatesLoading && studentOptions.length === 0"
            class="py-4 text-center text-xs text-text-subtle"
          >
            没有可添加的学生
          </div>
        </div>
        <div class="mt-4 flex justify-end gap-2">
          <Button
            variant="outline"
            data-testid="cancel-students"
            :disabled="studentSubmitting"
            @click="closeStudentDialog"
          >
            取消
          </Button>
          <Button
            data-testid="submit-students"
            :disabled="studentSelected.length === 0 || studentSubmitting"
            @click="submitStudents"
          >
            <PhSpinnerGap v-if="studentSubmitting" class="h-4 w-4 animate-spin" />
            {{ studentSubmitting ? '添加中' : `添加所选（${studentSelected.length}）` }}
          </Button>
        </div>
      </div>
    </div>

    <!-- 移除学生二次确认（ConfirmDialog：danger 实底；提交期关闭经 onDelDialogOpen 拦截） -->
    <ConfirmDialog
      :open="studentDeleting !== null"
      data-testid="confirm-student-del"
      title="移除学生"
      :description="`将移除「${studentDeleting?.displayName ?? ''}」的课程名额与报名关系，确认移除？`"
      confirm-text="确认移除"
      :loading="studentDeletingLoading"
      @update:open="onDelDialogOpen"
      @confirm="confirmDeleteStudent"
    />
  </section>
</template>
