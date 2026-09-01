<script setup lang="ts">
/**
 * 课程学生名单（2026-08-29 T2.4 重构：添加学生搜索迁移 remote-select 多选 +
 * Dialog 统一弹窗壳 + 页头刷新按钮）
 *
 * 职责：已选列表（username/displayName/enrolledAt）+ 添加 Dialog
 * （remote-select 多选 → POST 返回成功数 → 「成功添加 N 名」提示）+ 行移除二次确认。
 * 学生候选经统一键 ['user-pool', 'STUDENT'] 拉取（PERF-09：ensureQueryData 命中缓存
 * 本地过滤；后端无 keyword，fetcher 内客户端过滤 + 剔除已报名，契约 E）。
 */
import { computed, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { useRoute } from 'vue-router'
import {
  PhArrowClockwise,
  PhSpinnerGap,
  PhTrash,
  PhUserPlus,
  PhUsersThree,
} from '@phosphor-icons/vue'

import { Button } from '@/components/ui/button'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { DataTable } from '@/components/ui/data-table'
import { Dialog } from '@/components/ui/dialog'
import { EmptyState } from '@/components/ui/empty-state'
import { IconButton } from '@/components/ui/icon-button'
import { RemoteSelect } from '@/components/ui/remote-select'
import { fetchUserPool, userPoolKey } from '@/composables/course-queries'
import { ApiError, enrollmentApi } from '@/lib/api'
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
  isFetching,
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

function messageOf(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    return err.code === 503 ? '服务暂时不可用，请稍后重试' : err.message
  }
  return fallback
}

// ====================================================================
// 添加学生 Dialog（remote-select 多选，防抖 300ms + AbortController）
// ====================================================================

const studentDialogOpen = ref(false)
/** 已勾选待添加学生（remote-select modelValue 承载选项对象） */
const studentSelected = ref<UserDTO[]>([])
/** 待移除学生：非 null 时展示二次确认 Dialog */
const studentDeleting = ref<StudentDTO | null>(null)

/**
 * 学生候选 fetcher（remote-select 契约 E：防抖由组件负责）
 *
 * PERF-09：池数据经 queryClient.ensureQueryData 走统一键 ['user-pool', 'STUDENT']——
 * 命中缓存（30s staleTime 窗口内）即纯本地过滤 0 请求，未命中才整池拉取；
 * 后端 /admin/users 无 keyword 参数：拉池后按显示名/用户名客户端过滤并剔除已报名。
 * 不透传 signal：同键在途请求由 QueryClient 自动去重合并，语义等价。
 *
 * @param keyword 搜索关键字（空串 = 首屏候选）
 * @returns 可添加的学生候选
 */
async function fetchStudentOptions(keyword: string): Promise<UserDTO[]> {
  const pool = await queryClient.ensureQueryData({
    queryKey: userPoolKey('STUDENT'),
    queryFn: () => fetchUserPool('STUDENT'),
  })
  const kw = keyword.trim()
  return pool.filter(
    (u) =>
      !enrolledIds.value.has(u.id) &&
      (kw === '' || u.displayName.includes(kw) || u.username.includes(kw)),
  )
}

/** 打开添加学生 Dialog：清空勾选（候选由 remote-select 打开时自动拉取） */
function openStudentDialog() {
  studentDialogOpen.value = true
  studentSelected.value = []
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

/**
 * 学生选中集变化（remote-select 回抛联合类型按数组归一收窄）
 *
 * @param value 最新选中的学生集
 */
function onStudentsChange(value: UserDTO | UserDTO[] | null) {
  studentSelected.value = Array.isArray(value) ? value : value ? [value] : []
}

/** 提交批量添加：勾选非空校验 → 走 mutation */
function submitStudents() {
  if (studentSelected.value.length === 0) return
  submitStudentsMutation(studentSelected.value.map((u) => u.id))
}

// ====================================================================
// 移除学生（二次确认）
// ====================================================================

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
      <div class="flex items-center gap-2">
        <!-- 手动刷新（T2.3）：refetch 期间禁用防重复 -->
        <IconButton
          label="刷新"
          data-testid="refresh-students"
          :loading="isFetching"
          @click="refetch()"
        >
          <PhArrowClockwise class="h-4 w-4" />
        </IconButton>
        <Button size="sm" data-testid="add-students" @click="openStudentDialog">
          <PhUserPlus class="h-4 w-4" />
          添加学生
        </Button>
      </div>
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

    <!-- 添加学生 Dialog（remote-select 多选；提交期关闭经 canClose 拦截） -->
    <Dialog
      :open="studentDialogOpen"
      data-testid="student-dialog"
      title="添加学生"
      description="搜索并选择学生后批量添加（已报名学生自动过滤）"
      :can-close="!studentSubmitting"
      @update:open="(v: boolean) => !v && closeStudentDialog()"
    >
      <div class="mt-4">
        <RemoteSelect
          :model-value="studentSelected"
          :get-value="(u: UserDTO) => u.id"
          :get-label="(u: UserDTO) => u.displayName"
          :fetcher="fetchStudentOptions"
          multiple
          placeholder="搜索学生（显示名/用户名）"
          empty-text="没有可添加的学生"
          @update:model-value="onStudentsChange"
        />
        <p class="mt-1 text-xs text-text-subtle">
          已选 {{ studentSelected.length }} 名，可继续搜索追加
        </p>
      </div>
      <template #footer>
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
      </template>
    </Dialog>

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
