<script setup lang="ts">
/**
 * 课程教师分配（2026-08-29 T2.4 重构：remote-select 多选差集保存）
 *
 * 职责：授课教师集经 RemoteSelect 多选承载（防抖 300ms + AbortController，契约 E），
 * 保存按差集调既有端点——新增 POST / 移除 DELETE /admin/courses/{id}/teachers
 * （body 均为裸 JSON 数组，契约 E.3）。教师池经 GET /users?role=TEACHER 拉取
 * （后端无 keyword 参数，fetcher 内客户端过滤；选择器只列 TEACHER 角色兜底 R18）。
 *
 * PERF-09/11：课程详情与教师池分别消费统一键 ['course', id] / ['user-pool', 'TEACHER']
 * ——与详情壳/概览共享缓存（原合并单查询三键各自缓存的重复请求收敛），
 * 页面级加载/错误态由两查询的聚合视图承载（行为不变）。
 *
 * 线程安全注意：全部状态为组件私有 ref，无跨实例共享可变状态。
 */
import { computed, ref, watch } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { useRoute } from 'vue-router'
import { PhArrowClockwise, PhSpinnerGap } from '@phosphor-icons/vue'

import { Button } from '@/components/ui/button'
import { IconButton } from '@/components/ui/icon-button'
import { RemoteSelect } from '@/components/ui/remote-select'
import {
  courseDetailKey,
  fetchCourseDetail,
  fetchUserPool,
  userPoolKey,
} from '@/composables/course-queries'
import { ApiError, courseApi } from '@/lib/api'
import { showToast } from '@/lib/toast'
import type { UserDTO } from '@/lib/types'

const route = useRoute()
const queryClient = useQueryClient()
/** 课程 id（Long 字符串铁律） */
const courseId = computed(() => String(route.params.id ?? ''))

/** 课程详情：消费统一键 ['course', id]（PERF-11：与详情壳/概览共享缓存，Tab 首访切换去重） */
const {
  data: courseData,
  isLoading: courseLoading,
  isError: courseIsError,
  isFetching: courseIsFetching,
  error: courseQueryError,
  refetch: refetchCourse,
} = useQuery({
  queryKey: computed(() => courseDetailKey(courseId.value)),
  queryFn: () => fetchCourseDetail(courseId.value),
})

/** 教师池：消费统一键 ['user-pool', 'TEACHER']（PERF-09：与概览池查询/搜索 fetcher 共享缓存） */
const {
  data: teacherPoolData,
  isLoading: poolLoading,
  isError: poolIsError,
  isFetching: poolIsFetching,
  error: poolQueryError,
  refetch: refetchPool,
} = useQuery({
  queryKey: userPoolKey('TEACHER'),
  queryFn: () => fetchUserPool('TEACHER'),
})

const course = computed(() => courseData.value ?? null)
const teacherPool = computed(() => teacherPoolData.value ?? [])

/** 页面级聚合态：课程 + 教师池任一在加载/在途即整页骨架/刷新中（保持原合并单查询形态） */
const isLoading = computed(() => courseLoading.value || poolLoading.value)
const isFetching = computed(() => courseIsFetching.value || poolIsFetching.value)

/** 页面级加载失败横幅文案（课程/池任一失败透出；503 统一降级） */
const listError = computed(() => {
  if (courseIsError.value) {
    return messageOf(courseQueryError.value, '页面加载失败，请稍后重试')
  }
  if (poolIsError.value) {
    return messageOf(poolQueryError.value, '教师池加载失败，请稍后重试')
  }
  return ''
})

/** 聚合重拉：课程详情 + 教师池一起刷新（重试按钮/页头刷新/保存后基线重建共用） */
async function refetch() {
  await Promise.all([refetchCourse(), refetchPool()])
}

/** 已分配教师 id（课程 teacherIds，Long 字符串铁律） */
const assignedTeacherIds = computed(() => course.value?.teacherIds ?? [])

/** 草稿选中教师集（remote-select modelValue 承载选项对象；保存时与已分配集做差集） */
const draftTeachers = ref<UserDTO[]>([])
/** 草稿初始化标记（数据齐备后只回填一次，避免覆盖用户改动） */
let draftInitialized = false

/** 已分配教师明细（对象池 ∩ teacherIds；池未就绪时为 null 触发等待） */
const assignedTeachers = computed(() => {
  if (!course.value || !teacherPoolData.value) return null
  return teacherPool.value.filter((t) => assignedTeacherIds.value.includes(t.id))
})

// 数据齐备（课程 + 教师池）后回填草稿一次（setup 作用域 watch 随组件卸载自动停止）；
// immediate：warm cache 下数据在 watch 注册前已齐备且不再变化，不消费初始值则
// draftInitialized 永不为 true、草稿 chips 空白（BUG-02）；draftInitialized 一次化守卫
// 防后续重拉覆盖用户改动
watch(
  assignedTeachers,
  (list) => {
    if (list && !draftInitialized) {
      draftInitialized = true
      draftTeachers.value = [...list]
    }
  },
  { immediate: true },
)

/**
 * 保存分配（差集提交：新增 POST / 移除 DELETE，body 裸数组——契约 E.3）
 *
 * 成功后重拉页面查询刷新已分配基线；失败 toast 提示（草稿保留可重试）。
 */
const { isPending: saving, mutate: saveTeachersMutation } = useMutation({
  mutationFn: async () => {
    const current = draftTeachers.value.map((t) => t.id)
    const added = current.filter((id) => !assignedTeacherIds.value.includes(id))
    const removed = assignedTeacherIds.value.filter((id) => !current.includes(id))
    if (added.length > 0) {
      await courseApi.addTeachers(courseId.value, added)
    }
    if (removed.length > 0) {
      await courseApi.removeTeachers(courseId.value, removed)
    }
  },
  onSuccess: async () => {
    showToast('教师分配已保存', 'success')
    // 重建草稿基线：聚合重拉（课程统一键 + 教师池）后 watch 以新数据刷新已分配集
    // （共享键缓存同步更新，详情壳/概览下次挂载即见最新 teacherIds）
    await refetch()
  },
  onError: (err) => {
    showToast(messageOf(err, '教师分配失败，请稍后重试'), 'danger')
  },
})

function messageOf(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    return err.code === 503 ? '服务暂时不可用，请稍后重试' : err.message
  }
  return fallback
}

/**
 * 教师远程搜索 fetcher（remote-select 契约 E：防抖由组件负责）
 *
 * PERF-09：池数据经 queryClient.ensureQueryData 走统一键 ['user-pool', 'TEACHER']——
 * 命中缓存（30s staleTime 窗口内）即纯本地过滤 0 请求，未命中才整池拉取；
 * 后端 /admin/users 无 keyword 参数：拉池后按显示名/用户名客户端过滤。
 * 不透传 signal：同键在途请求由 QueryClient 自动去重合并，语义等价。
 *
 * @param keyword 搜索关键字（空串 = 首屏候选全量）
 * @returns 命中的教师列表
 */
async function fetchTeachers(keyword: string): Promise<UserDTO[]> {
  const pool = await queryClient.ensureQueryData({
    queryKey: userPoolKey('TEACHER'),
    queryFn: () => fetchUserPool('TEACHER'),
  })
  const kw = keyword.trim()
  if (kw === '') return pool
  return pool.filter((t) => t.displayName.includes(kw) || t.username.includes(kw))
}

/**
 * 教师选中集变化（契约 E.3：保存时与已分配集做差集）
 *
 * @param value remote-select 回抛的选中集（多选为对象数组；联合类型按数组归一收窄）
 */
function onTeachersChange(value: UserDTO | UserDTO[] | null) {
  draftTeachers.value = Array.isArray(value) ? value : value ? [value] : []
}

/** 草稿与已分配集存在差异（驱动保存按钮可用态） */
const hasChanges = computed(() => {
  const current = new Set(draftTeachers.value.map((t) => t.id))
  const original = assignedTeacherIds.value
  return current.size !== original.length || original.some((id) => !current.has(id))
})

/** 保存分配：无差异不发请求 */
function saveAssignment() {
  if (!hasChanges.value || saving.value) return
  saveTeachersMutation()
}
</script>

<template>
  <section v-reveal class="rounded-2xl border border-border bg-surface shadow-xs">
    <div class="flex items-center justify-between gap-4 px-6 py-[18px]">
      <h2 class="text-lg font-extrabold tracking-tight text-text">教师分配</h2>
      <div class="flex items-center gap-2">
        <p class="text-xs text-text-subtle">选择教师后按增删差集一次保存</p>
        <!-- 手动刷新（T2.3）：refetch 期间禁用防重复 -->
        <IconButton
          label="刷新"
          data-testid="refresh-teachers"
          :loading="isFetching"
          @click="refetch()"
        >
          <PhArrowClockwise class="h-4 w-4" />
        </IconButton>
      </div>
    </div>

    <!-- 加载骨架 -->
    <div v-if="isLoading" data-testid="teachers-skeleton" class="animate-pulse space-y-4 px-6 pb-6">
      <div class="h-9 rounded-xl bg-surface-2" />
      <div class="h-10 rounded-xl bg-surface-2" />
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

    <div v-else class="space-y-4 px-6 pb-6">
      <!-- 教师多选（remote-select：防抖 300ms + 取消，已分配自动回显为 chips） -->
      <div>
        <span class="mb-1.5 block text-sm font-medium text-text">授课教师</span>
        <RemoteSelect
          :model-value="draftTeachers"
          :get-value="(t: UserDTO) => t.id"
          :get-label="(t: UserDTO) => t.displayName"
          :fetcher="fetchTeachers"
          :initial-options="assignedTeachers ?? []"
          multiple
          placeholder="搜索教师（显示名/用户名），已分配教师自动回显"
          empty-text="没有匹配的教师"
          @update:model-value="onTeachersChange"
        />
        <p class="mt-1 text-xs text-text-subtle">
          当前已分配 {{ assignedTeacherIds.length }} 名；保存后按增删差集更新关联
        </p>
      </div>

      <!-- 保存行：差集提交（无差异禁用 + spinner 防重复） -->
      <Button
        class="self-start"
        data-testid="teacher-assign"
        :disabled="!hasChanges || saving"
        @click="saveAssignment"
      >
        <PhSpinnerGap v-if="saving" class="h-4 w-4 animate-spin" />
        {{ saving ? '保存中' : hasChanges ? `保存分配（${draftTeachers.length}）` : '无变动' }}
      </Button>
    </div>
  </section>
</template>
