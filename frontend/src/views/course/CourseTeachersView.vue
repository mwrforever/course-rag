<script setup lang="ts">
/**
 * 课程教师分配（UI 重构 2026-08-25 从 CourseEditView 拆出）
 *
 * 职责：双栏（已分配 / 可选=全量 TEACHER 剔除已分配 + 搜索过滤）+
 * POST [ids] 分配 + 移除 DELETE 带 body（axios data 写法，设计 §2.4.4）。
 * 教师用户池经 GET /users?role=TEACHER 一次拉取（后端无 keyword 参数，搜索客户端过滤，
 * 选择器只列 TEACHER 角色兜底 R18）。
 */
import { computed, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { useRoute } from 'vue-router'
import { PhSpinnerGap } from '@phosphor-icons/vue'

import { Button } from '@/components/ui/button'
import { ApiError, courseApi, userApi } from '@/lib/api'
import { showToast } from '@/lib/toast'
import type { UserDTO } from '@/lib/types'

const route = useRoute()
/** 课程 id（Long 字符串铁律） */
const courseId = computed(() => String(route.params.id ?? ''))

/** 待分配勾选的教师 id（复选框 v-model 数组） */
const teacherSelected = ref<string[]>([])
const teacherSearch = ref('')
/** 行内移除进行中的教师 id（原实现语义：仅该行 spinner） */
const teacherRemovingId = ref('')

/** 页面级加载：课程（teacherIds）+ 教师候选池 并发拉取（整体错误态横幅重试；vue-query 合并单查询） */
const {
  data,
  isLoading,
  isError,
  error: queryError,
  refetch,
} = useQuery({
  queryKey: computed(() => ['course-teachers', courseId.value]),
  queryFn: async () => {
    const [c, teacherPage] = await Promise.all([
      courseApi.get(courseId.value),
      userApi.list({ role: 'TEACHER', size: 100 }),
    ])
    return {
      course: c,
      // 教师池按角色客户端兜底过滤（设计 R18：后端不校验，选择器只列 TEACHER）
      teacherPool: (teacherPage.records ?? []).filter((u) => u.role === 'TEACHER'),
    }
  },
})

const course = computed(() => data.value?.course ?? null)
const teacherPool = computed(() => data.value?.teacherPool ?? [])

/** 页面级加载失败横幅文案（queryError 非空时透出；503 统一降级） */
const listError = computed(() =>
  isError.value ? messageOf(queryError.value, '页面加载失败，请稍后重试') : '',
)

/** 已分配教师 id（课程 teacherIds，Long 字符串铁律） */
const assignedTeacherIds = computed(() => course.value?.teacherIds ?? [])

const queryClient = useQueryClient()

/**
 * 分配/移除成功后的刷新：按查询键重拉（course + 候选池合并单查询，低频操作全量重拉可接受——
 * 评估结论：候选池 ≤100 行、分配/移除低频，拆双查询需整页四态合并且无性能收益，保持合并）
 *
 * 重拉失败（如后端瞬时故障）以 toast 提示（恢复原 refreshCourse 的「课程刷新失败」交互），
 * 不静默；页面错误横幅由查询自身的错误态兜底。
 */
/**
 * 分配/移除成功后的刷新：await 列表查询 refetch（v5 语义：失败也 resolve，不 reject）后
 * 以查询状态判定重拉失败（status 变 error 即 fetch 失败），失败以 toast 提示
 * （恢复原 refreshCourse 的「课程刷新失败」交互），不静默；页面错误横幅由查询错误态兜底。
 *
 * 候选池与课程同键合并单查询（['course-teachers', courseId]），低频操作全量重拉可接受——
 * 评估结论见 TASK.md §6（拆双查询需整页四态合并且无性能收益，保持合并）。
 */
async function refreshTeachers() {
  await refetch()
  const state = queryClient.getQueryState(['course-teachers', courseId.value])
  if (state?.error) {
    showToast('课程刷新失败，请重试或刷新页面', 'danger')
  }
}

function messageOf(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    return err.code === 503 ? '服务暂时不可用，请稍后重试' : err.message
  }
  return fallback
}

/**
 * 可选教师过滤：角色 TEACHER 兜底（R18）＋ 剔除已分配 ＋ 搜索关键词
 * （displayName/username 子串命中，后端 /users 无 keyword 参数）
 */
const availableTeachers = computed(() =>
  teacherPool.value.filter(
    (t) =>
      t.role === 'TEACHER' &&
      !assignedTeacherIds.value.includes(t.id) &&
      (teacherSearch.value.trim() === '' ||
        t.displayName.includes(teacherSearch.value.trim()) ||
        t.username.includes(teacherSearch.value.trim())),
  ),
)

/** 已分配教师明细：id → 用户对象（候选池不在场时以 id 兜底展示） */
const assignedTeachers = computed(() =>
  assignedTeacherIds.value
    .map((id) => teacherPool.value.find((t) => t.id === id))
    .filter((t): t is UserDTO => Boolean(t)),
)

/** 分配所选教师提交（POST /{id}/teachers 数组 body；成功后重拉双栏，失败以 toast 提示） */
const { isPending: teacherAssigning, mutate: assignTeachersMutation } = useMutation({
  mutationFn: (ids: string[]) => courseApi.addTeachers(courseId.value, ids),
  onSuccess: () => {
    showToast('教师分配成功', 'success')
    teacherSelected.value = []
    void refreshTeachers()
  },
  onError: (err) => {
    showToast(messageOf(err, '教师分配失败，请稍后重试'), 'danger')
  },
})

/** 分配所选教师：勾选非空校验 → 走 mutation */
function assignTeachers() {
  if (teacherSelected.value.length === 0) return
  assignTeachersMutation(teacherSelected.value)
}

/** 移除教师提交（DELETE /{id}/teachers 带 body [id]；行内 spinner 由 teacherRemovingId 控制） */
const { mutate: removeTeacherMutation } = useMutation({
  mutationFn: (id: string) => courseApi.removeTeachers(courseId.value, [id]),
  onSuccess: () => {
    showToast('已移除教师', 'success')
    teacherRemovingId.value = ''
    void refreshTeachers()
  },
  onError: (err) => {
    teacherRemovingId.value = ''
    showToast(messageOf(err, '移除教师失败，请稍后重试'), 'danger')
  },
})

/** 移除教师：仅该行 spinner，完成/失败由 mutation 回调处理 */
function removeTeacher(t: UserDTO) {
  teacherRemovingId.value = t.id
  removeTeacherMutation(t.id)
}
</script>

<template>
  <section class="rounded-xl border border-border bg-surface">
    <div class="flex items-center justify-between border-b border-border px-6 py-4">
      <h2 class="text-base font-semibold text-text">教师分配</h2>
    </div>

    <!-- 加载骨架 -->
    <div v-if="isLoading" data-testid="teachers-skeleton" class="animate-pulse space-y-4 p-6">
      <div class="h-9 rounded-lg bg-surface-2" />
      <div class="h-40 rounded-lg bg-surface-2" />
    </div>

    <!-- 加载错误：横幅 + 重试 -->
    <div
      v-else-if="listError"
      role="alert"
      class="flex items-center justify-between gap-4 px-6 py-4"
    >
      <span class="text-sm text-danger">{{ listError }}</span>
      <Button variant="outline" size="sm" @click="refetch">重试</Button>
    </div>

    <div v-else class="flex flex-1 flex-col gap-4 p-6">
      <!-- 搜索过滤（后端 /users 无 keyword 参数，客户端过滤） -->
      <input
        v-model="teacherSearch"
        type="text"
        data-testid="teacher-search"
        aria-label="搜索教师"
        placeholder="搜索教师（显示名/用户名）"
        class="h-9 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
      />

      <!-- 已分配列表（含移除按钮） -->
      <div>
        <p class="mb-1.5 text-xs font-semibold tracking-wider text-text-subtle">
          已分配 {{ assignedTeachers.length }}
        </p>
        <div
          v-if="assignedTeachers.length === 0"
          class="rounded-lg bg-surface-2 px-3 py-2 text-xs text-text-subtle"
        >
          暂无已分配教师
        </div>
        <ul v-else class="space-y-1.5">
          <li
            v-for="t in assignedTeachers"
            :key="t.id"
            :data-testid="`teacher-assigned-${t.id}`"
            class="flex items-center justify-between gap-2 rounded-lg bg-surface-2 px-3 py-2"
          >
            <span class="truncate text-sm text-text">{{ t.displayName }}</span>
            <Button
              variant="ghost"
              size="sm"
              class="h-6 px-2 text-xs text-danger hover:bg-danger/5"
              :data-testid="`teacher-remove-${t.id}`"
              :disabled="teacherRemovingId === t.id"
              @click="removeTeacher(t)"
            >
              <PhSpinnerGap v-if="teacherRemovingId === t.id" class="h-3 w-3 animate-spin" />
              移除
            </Button>
          </li>
        </ul>
      </div>

      <!-- 可选列表：复选框勾选待分配 -->
      <div class="min-h-0 flex-1">
        <p class="mb-1.5 text-xs font-semibold tracking-wider text-text-subtle">可选教师</p>
        <div class="max-h-56 space-y-1.5 overflow-y-auto pr-1">
          <label
            v-for="t in availableTeachers"
            :key="t.id"
            :data-testid="`teacher-available-${t.id}`"
            class="flex cursor-pointer items-center gap-2 rounded-lg border border-border px-3 py-2 transition-colors duration-150 hover:bg-surface-2"
          >
            <input
              v-model="teacherSelected"
              type="checkbox"
              :value="t.id"
              :data-testid="`teacher-check-${t.id}`"
              class="h-4 w-4 accent-brand"
            />
            <span class="truncate text-sm text-text">{{ t.displayName }}</span>
            <span class="truncate text-xs text-text-subtle">{{ t.username }}</span>
          </label>
          <div
            v-if="availableTeachers.length === 0"
            data-testid="teacher-available-empty"
            class="rounded-lg bg-surface-2 px-3 py-2 text-xs text-text-subtle"
          >
            没有匹配的教师
          </div>
        </div>
      </div>

      <!-- 分配按钮：POST [ids] 数组 -->
      <Button
        data-testid="teacher-assign"
        :disabled="teacherSelected.length === 0 || teacherAssigning"
        @click="assignTeachers"
      >
        <PhSpinnerGap v-if="teacherAssigning" class="h-4 w-4 animate-spin" />
        分配所选（{{ teacherSelected.length }}）
      </Button>
    </div>
  </section>
</template>
