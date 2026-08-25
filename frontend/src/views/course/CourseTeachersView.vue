<script setup lang="ts">
/**
 * 课程教师分配（UI 重构 2026-08-25 从 CourseEditView 拆出）
 *
 * 职责：双栏（已分配 / 可选=全量 TEACHER 剔除已分配 + 搜索过滤）+
 * POST [ids] 分配 + 移除 DELETE 带 body（axios data 写法，设计 §2.4.4）。
 * 教师用户池经 GET /users?role=TEACHER 一次拉取（后端无 keyword 参数，搜索客户端过滤，
 * 选择器只列 TEACHER 角色兜底 R18）。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { PhSpinnerGap } from '@phosphor-icons/vue'

import { Button } from '@/components/ui/button'
import { ApiError, courseApi, userApi } from '@/lib/api'
import { showToast } from '@/lib/toast'
import type { CourseDTO, UserDTO } from '@/lib/types'

const route = useRoute()
/** 课程 id（Long 字符串铁律） */
const courseId = (): string => String(route.params.id ?? '')

/** 课程主数据（teacherIds 驱动双栏） */
const course = ref<CourseDTO | null>(null)
/** 教师候选池：GET /users?role=TEACHER 一次拉取，后续客户端过滤（无 keyword 参数） */
const teacherPool = ref<UserDTO[]>([])
/** 待分配勾选的教师 id（复选框 v-model 数组） */
const teacherSelected = ref<string[]>([])
const teacherSearch = ref('')
const teacherAssigning = ref(false)
const teacherRemovingId = ref('')
const loading = ref(true)
const error = ref('')

function messageOf(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    return err.code === 503 ? '服务暂时不可用，请稍后重试' : err.message
  }
  return fallback
}

/**
 * 页面级加载：课程（teacherIds）+ 教师候选池 并发拉取（整体错误态横幅重试）
 */
async function loadPage() {
  loading.value = true
  error.value = ''
  try {
    const [c, teacherPage] = await Promise.all([
      courseApi.get(courseId()),
      userApi.list({ role: 'TEACHER', size: 100 }),
    ])
    course.value = c
    // 教师池按角色客户端兜底过滤（设计 R18：后端不校验，选择器只列 TEACHER）
    teacherPool.value = (teacherPage.records ?? []).filter((u) => u.role === 'TEACHER')
  } catch (err) {
    error.value = messageOf(err, '页面加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

/** 教师分配变更后轻刷新：仅重拉课程（teacherIds 驱动双栏重排） */
async function refreshCourse() {
  try {
    course.value = await courseApi.get(courseId())
  } catch (err) {
    showToast(messageOf(err, '课程刷新失败，请稍后重试'), 'danger')
  }
}

/** 已分配教师 id（课程 teacherIds，Long 字符串铁律） */
const assignedTeacherIds = computed(() => course.value?.teacherIds ?? [])

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

/** 分配所选教师：POST /{id}/teachers 数组 body → toast → 清空勾选 → 重拉课程刷新双栏 */
async function assignTeachers() {
  if (teacherSelected.value.length === 0) return
  teacherAssigning.value = true
  try {
    await courseApi.addTeachers(courseId(), teacherSelected.value)
    showToast('教师分配成功', 'success')
    teacherSelected.value = []
    await refreshCourse()
  } catch (err) {
    showToast(messageOf(err, '教师分配失败，请稍后重试'), 'danger')
  } finally {
    teacherAssigning.value = false
  }
}

/** 移除教师：DELETE /{id}/teachers 带 body [id]（axios data 写法）→ toast → 重拉课程 */
async function removeTeacher(t: UserDTO) {
  teacherRemovingId.value = t.id
  try {
    await courseApi.removeTeachers(courseId(), [t.id])
    showToast('已移除教师', 'success')
    await refreshCourse()
  } catch (err) {
    showToast(messageOf(err, '移除教师失败，请稍后重试'), 'danger')
  } finally {
    teacherRemovingId.value = ''
  }
}

onMounted(() => {
  void loadPage()
})
</script>

<template>
  <section class="rounded-xl border border-border bg-surface">
    <div class="flex items-center justify-between border-b border-border px-6 py-4">
      <h2 class="text-base font-semibold text-text">教师分配</h2>
    </div>

    <!-- 加载骨架 -->
    <div v-if="loading" data-testid="teachers-skeleton" class="animate-pulse space-y-4 p-6">
      <div class="h-9 rounded-lg bg-surface-2" />
      <div class="h-40 rounded-lg bg-surface-2" />
    </div>

    <!-- 加载错误：横幅 + 重试 -->
    <div v-else-if="error" role="alert" class="flex items-center justify-between gap-4 px-6 py-4">
      <span class="text-sm text-danger">{{ error }}</span>
      <Button variant="outline" size="sm" @click="loadPage">重试</Button>
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
