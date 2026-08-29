<script setup lang="ts">
/**
 * 课程教师分配（2026-08-29 T2.4 重构：remote-select 多选差集保存）
 *
 * 职责：授课教师集经 RemoteSelect 多选承载（防抖 300ms + AbortController，契约 E），
 * 保存按差集调既有端点——新增 POST / 移除 DELETE /admin/courses/{id}/teachers
 * （body 均为裸 JSON 数组，契约 E.3）。教师池经 GET /users?role=TEACHER 拉取
 * （后端无 keyword 参数，fetcher 内客户端过滤；选择器只列 TEACHER 角色兜底 R18）。
 *
 * 线程安全注意：全部状态为组件私有 ref，无跨实例共享可变状态。
 */
import { computed, ref, watch } from 'vue'
import { useMutation, useQuery } from '@tanstack/vue-query'
import { useRoute } from 'vue-router'
import { PhArrowClockwise, PhSpinnerGap } from '@phosphor-icons/vue'

import { Button } from '@/components/ui/button'
import { IconButton } from '@/components/ui/icon-button'
import { RemoteSelect } from '@/components/ui/remote-select'
import { ApiError, courseApi, userApi } from '@/lib/api'
import { showToast } from '@/lib/toast'
import type { UserDTO } from '@/lib/types'

const route = useRoute()
/** 课程 id（Long 字符串铁律） */
const courseId = computed(() => String(route.params.id ?? ''))

/** 页面级加载：课程（teacherIds）+ 教师候选池 并发拉取（整体错误态横幅重试；vue-query 合并单查询） */
const {
  data,
  isLoading,
  isError,
  isFetching,
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

/** 草稿选中教师集（remote-select modelValue 承载选项对象；保存时与已分配集做差集） */
const draftTeachers = ref<UserDTO[]>([])
/** 草稿初始化标记（数据齐备后只回填一次，避免覆盖用户改动） */
let draftInitialized = false

/** 已分配教师明细（对象池 ∩ teacherIds；池未就绪时为 null 触发等待） */
const assignedTeachers = computed(() => {
  if (!course.value || !data.value) return null
  return teacherPool.value.filter((t) => assignedTeacherIds.value.includes(t.id))
})

// 数据齐备（课程 + 教师池）后回填草稿一次（setup 作用域 watch 随组件卸载自动停止）
watch(assignedTeachers, (list) => {
  if (list && !draftInitialized) {
    draftInitialized = true
    draftTeachers.value = [...list]
  }
})

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
    // 重建草稿基线：重拉后 watch 会以新数据刷新已分配集（草稿已与提交一致）
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
 * 教师远程搜索 fetcher（remote-select 契约 E：防抖与取消由组件负责）
 *
 * 后端 /admin/users 无 keyword 参数：整池拉取后客户端按显示名/用户名过滤；
 * signal 透传 axios，新输入取消旧请求。
 *
 * @param keyword 搜索关键字（空串 = 首屏候选全量）
 * @param signal 取消信号
 * @returns 命中的教师列表
 */
async function fetchTeachers(keyword: string, signal: AbortSignal): Promise<UserDTO[]> {
  const res = await userApi.list({ role: 'TEACHER', size: 100, signal })
  const pool = (res.records ?? []).filter((u) => u.role === 'TEACHER')
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
