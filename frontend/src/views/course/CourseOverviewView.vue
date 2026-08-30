<script setup lang="ts">
/**
 * 课程概览（基础信息表单，2026-08-29 T2.2 重构：封面上传文件化 + 报名链接只读 +
 * 分类预置下拉 + 授课教师 remote-select 多选 + zod 全字段校验）
 *
 * 职责：
 * - 封面走 image-upload（POST /admin/courses/cover 契约 D，上传回传相对 URL 随课程提交）；
 * - 报名链接服务端自动生成（契约 A）：编辑态只读展示 + 一键复制，新建态提示「保存后自动生成」；
 * - 分类为预置选项下拉（datalist，允许输入自定义值）；
 * - 授课教师 remote-select 多选（防抖 300ms + AbortController，契约 E）；保存按差集调
 *   POST/DELETE /admin/courses/{id}/teachers（body 裸数组，契约 E.3）；
 * - instructorName 在为空时自动预填第一位教师姓名（仍可手改，契约 E.3）；
 * - zod 全字段校验（标题必填、价格数字、封面 URL 格式、课时数字），错误内联字段下方；
 * - 提交 loading 防重复；成功后按 queryKey 失效（admin-courses / course-form / course-teachers）。
 *
 * 线程安全注意：全部状态为组件私有 ref；复制成功的 1.5s 复位定时器在卸载时清理。
 */
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { useRoute, useRouter } from 'vue-router'
import { z } from 'zod'
import {
  PhArticle,
  PhCalendarBlank,
  PhChalkboardTeacher,
  PhCheck,
  PhCopy,
  PhSpinnerGap,
  PhUsers,
  PhX,
} from '@phosphor-icons/vue'

import { Button } from '@/components/ui/button'
import { ImageUpload } from '@/components/ui/image-upload'
import { Input } from '@/components/ui/input'
import { RemoteSelect } from '@/components/ui/remote-select'
import { Select } from '@/components/ui/select'
import { StatCard } from '@/components/ui/stat-card'
import { Textarea } from '@/components/ui/textarea'
import { ApiError, courseApi, userApi } from '@/lib/api'
import { COURSE_CATEGORY_PRESETS } from '@/lib/constants'
import { showToast } from '@/lib/toast'
import type { CourseDTO, UpdateCourseRequest, UserDTO } from '@/lib/types'

/**
 * 课程表单 zod 校验（契约 T2.2 全字段）
 *
 * - 标题必填；价格/课时允许留空，填写时必须为非负数字；
 * - 封面 URL 允许留空或 http(s) 绝对地址 / 斜杠开头相对路径（上传端点回传相对路径）。
 */
const courseFormSchema = z.object({
  title: z.string().min(1, '请输入课程标题'),
  coverImage: z
    .string()
    .refine((v) => v === '' || /^https?:\/\//.test(v) || v.startsWith('/'), '封面地址格式不正确'),
  price: z
    .string()
    .refine((v) => v === '' || (!Number.isNaN(Number(v)) && Number(v) >= 0), '价格须为非负数字'),
  duration: z.string().refine((v) => v === '' || /^\d+(\.\d+)?$/.test(v.trim()), '课时须为数字'),
})

/** 基础表单承载：价格以字符串承载，提交时数值化（避免输入过程 Number 精度抖动） */
const form = reactive({
  title: '',
  description: '',
  coverImage: '',
  category: '',
  instructorName: '',
  price: '',
  duration: '',
  status: 'ACTIVE' as 'ACTIVE' | 'ARCHIVED',
})

/** 标签 chips：回车添加、X 删除（提交体为字符串数组） */
const tags = ref<string[]>([])
const tagInput = ref('')
/** 字段级错误（zod 校验失败按字段名内联展示，key 与表单字段同名） */
const fieldErrors = reactive<Record<string, string>>({})

const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()

/** 新建模式：路由名 course-new；编辑模式 course-detail（带 :id） */
const isNew = route.name === 'course-new'
/** 课程 id：编辑模式取自路由参数（Long 字符串铁律） */
const courseId = computed(() => String(route.params.id ?? ''))

/** 编辑模式课程加载（新建模式 enabled=false 不拉取，表单直开；查询键含路由 id 派生态） */
const {
  data: courseData,
  isLoading,
  isError,
  error: queryError,
  refetch,
} = useQuery({
  queryKey: computed(() => ['course-form', courseId.value]),
  queryFn: () => courseApi.get(courseId.value),
  enabled: !isNew,
  // 表单回填后不随后台 refetch 覆盖未保存编辑：禁用窗口聚焦重拉
  refetchOnWindowFocus: false,
})

// ====================================================================
// 授课教师（remote-select 多选，契约 E / E.3）
// ====================================================================

/** 教师池（编辑态：teacherIds → 教师对象回显；后端 /users 无 keyword，fetcher 内客户端过滤） */
const { data: teacherPoolData } = useQuery({
  queryKey: ['teacher-pool'],
  queryFn: async () => {
    const res = await userApi.list({ role: 'TEACHER', size: 100 })
    return (res.records ?? []).filter((u) => u.role === 'TEACHER')
  },
  enabled: !isNew,
})
const teacherPool = computed(() => teacherPoolData.value ?? [])

/** 当前选中的授课教师（remote-select modelValue 承载选项对象本身） */
const selectedTeachers = ref<UserDTO[]>([])
/** 打开表单时的原始教师 id 集（保存时计算差集） */
const originalTeacherIds = ref<string[]>([])
/** 教师回显初始化标记（课程与教师池双数据齐备后只回填一次，避免覆盖用户改动） */
let teachersInitialized = false

/** 编辑态教师回显数据：teacherIds ∩ 教师池（课程与教师池双数据齐备前为 null，齐备后触发回填） */
const initialTeachers = computed(() => {
  const ids = courseData.value?.teacherIds
  if (!ids || !teacherPoolData.value) return null
  return teacherPool.value.filter((t) => ids.includes(t.id))
})

watch(initialTeachers, (list) => {
  if (list && !teachersInitialized) {
    teachersInitialized = true
    selectedTeachers.value = [...list]
    originalTeacherIds.value = [...(courseData.value?.teacherIds ?? [])]
    // 契约 E.3：讲师名为空时以第一位教师姓名预填（回显与交互两条路径同一规则，仍可手改）
    if (list.length > 0 && form.instructorName.trim() === '') {
      form.instructorName = list[0].displayName
    }
  }
})

/**
 * 教师远程搜索 fetcher（remote-select 契约 E：防抖与取消由组件负责）
 *
 * 后端 /admin/users 仅支持 page/size/role/status（无 keyword），此处整池拉取后
 * 客户端按显示名/用户名过滤；signal 透传 axios，新输入取消旧请求。
 *
 * @param keyword 搜索关键字（空串 = 首屏候选全量）
 * @param signal 取消信号（透传 api 层）
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
 * 教师选中集变化（契约 E.3：instructorName 为空时自动预填第一位教师姓名，仍可手改）
 *
 * @param value remote-select 回抛的选中集（联合类型按数组归一收窄）
 */
function onTeachersChange(value: UserDTO | UserDTO[] | null) {
  const list = Array.isArray(value) ? value : value ? [value] : []
  selectedTeachers.value = list
  if (list.length > 0 && form.instructorName.trim() === '') {
    form.instructorName = list[0].displayName
  }
}

// ====================================================================
// 表单回填与辅助
// ====================================================================

/** 加载完成回填表单（本查询无自动刷新，表单编辑不受缓存覆盖；重进页面命中 30s 缓存即回填） */
watch(courseData, (c) => {
  if (c) applyCourseToForm(c)
})

/** 编辑模式加载失败横幅文案（queryError 非空时透出；503 统一降级） */
const listError = computed(() =>
  isError.value ? messageOf(queryError.value, '课程加载失败，请稍后重试') : '',
)

/** 统计区四卡（编辑态且课程加载完成后渲染；全部为课程真实字段，无装饰性假数） */
const statCards = computed(() => {
  const c = courseData.value
  if (!c) return []
  return [
    { label: '学习人数', value: Number(c.learningCount), tone: 'success' as const },
    { label: '内容板块', value: c.contents?.length ?? 0, tone: 'brand' as const },
    { label: '排期数量', value: c.schedules?.length ?? 0, tone: 'warning' as const },
    { label: '分配教师', value: c.teacherIds?.length ?? 0, tone: 'danger' as const },
  ]
})

function messageOf(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    return err.code === 503 ? '服务暂时不可用，请稍后重试' : err.message
  }
  return fallback
}

/** 标签回车添加：trim 后去重入列，输入框清空（测试/交互同契约） */
function addTag() {
  const value = tagInput.value.trim()
  if (value && !tags.value.includes(value)) {
    tags.value.push(value)
  }
  tagInput.value = ''
}

/** 删除标签 chip */
function removeTag(tag: string) {
  tags.value = tags.value.filter((t) => t !== tag)
}

/** 课程回填基础表单（价格数值 → 字符串承载；标签数组化） */
function applyCourseToForm(c: CourseDTO) {
  form.title = c.title
  form.description = c.description ?? ''
  form.coverImage = c.coverImage ?? ''
  form.category = c.category ?? ''
  form.instructorName = c.instructorName ?? ''
  form.price = c.price === 0 ? '' : String(c.price)
  form.duration = c.duration ?? ''
  form.status = c.status === 'ARCHIVED' ? 'ARCHIVED' : 'ACTIVE'
  tags.value = [...(c.tags ?? [])]
}

// ====================================================================
// 报名链接只读复制（契约 A.2.4：服务端生成，编辑态只读展示 + 一键复制）
// ====================================================================

/** 复制成功标记（1.5s 内图标切换为对勾后自动复位） */
const linkCopied = ref(false)
/** 复位定时器句柄（卸载清理防泄漏） */
let copyResetTimer: ReturnType<typeof setTimeout> | null = null

/** 一键复制报名链接（clipboard API；失败 toast 提示手动复制） */
async function copyEnrollmentLink() {
  const link = courseData.value?.enrollmentLink ?? ''
  if (!link) return
  try {
    if (!navigator.clipboard?.writeText) {
      throw new Error('clipboard unavailable')
    }
    await navigator.clipboard.writeText(link)
    linkCopied.value = true
    if (copyResetTimer) clearTimeout(copyResetTimer)
    // 1.5s 后对勾复位回复制图标（契约 A.2.4）
    copyResetTimer = setTimeout(() => {
      linkCopied.value = false
    }, 1500)
  } catch {
    showToast('复制失败，请手动复制链接', 'danger')
  }
}

onUnmounted(() => {
  if (copyResetTimer) clearTimeout(copyResetTimer)
})

// ====================================================================
// 保存（新建 create + 教师落库 / 编辑 update + 教师差集）
// ====================================================================

/**
 * 基础信息保存（新建/编辑分派；isPending 驱动按钮禁用与文案）
 *
 * 新建：create（enrollmentLink 服务端生成不传）→ 有选中教师时 POST 裸数组落库 →
 * 跳转 /courses/{id} 继续编辑；编辑：update + 教师差集（新增 POST / 移除 DELETE，
 * body 均为裸 JSON 数组，契约 E.3）。
 */
const { isPending: saving, mutate: saveBasicMutation } = useMutation({
  mutationFn: async (): Promise<CourseDTO | undefined> => {
    const common = {
      title: form.title,
      description: form.description,
      coverImage: form.coverImage,
      category: form.category,
      instructorName: form.instructorName,
      price: form.price === '' ? undefined : Number(form.price),
      duration: form.duration,
      tags: tags.value.length > 0 ? tags.value : null,
    }
    if (isNew) {
      const created = await courseApi.create(common)
      // 新建后教师落库（E.3：差集调用既有端点，body 裸数组）
      if (selectedTeachers.value.length > 0) {
        await courseApi.addTeachers(
          created.id,
          selectedTeachers.value.map((t) => t.id),
        )
      }
      return created
    }
    const payload: UpdateCourseRequest = { ...common, status: form.status }
    await courseApi.update(courseId.value, payload)
    // 编辑态教师差集：新增 POST / 移除 DELETE（端点幂等 + 409 兜底由后端承载）。
    // 教师池加载失败（未完成回显）时跳过差集——避免误判全量移除造成教师关联丢失
    if (teachersInitialized) {
      const current = selectedTeachers.value.map((t) => t.id)
      const added = current.filter((id) => !originalTeacherIds.value.includes(id))
      const removed = originalTeacherIds.value.filter((id) => !current.includes(id))
      if (added.length > 0) {
        await courseApi.addTeachers(courseId.value, added)
      }
      if (removed.length > 0) {
        await courseApi.removeTeachers(courseId.value, removed)
      }
      originalTeacherIds.value = current
    }
    return undefined
  },
  onSuccess: async (created) => {
    // 写后读一致：课程列表 / 表单缓存 / 教师分配页统一失效
    void queryClient.invalidateQueries({ queryKey: ['admin-courses'] })
    if (isNew) {
      showToast('课程创建成功', 'success')
      if (created) await router.push({ name: 'course-detail', params: { id: created.id } })
    } else {
      showToast('课程信息已保存', 'success')
      void queryClient.invalidateQueries({ queryKey: ['course-form'] })
      void queryClient.invalidateQueries({ queryKey: ['course-teachers'] })
    }
  },
  onError: (err) => {
    showToast(messageOf(err, '保存失败，请稍后重试'), 'danger')
  },
})

/**
 * 基础信息保存入口：zod 全字段校验（失败按字段内联报错不发请求）→ 走 mutation
 */
function saveBasic() {
  const parsed = courseFormSchema.safeParse({
    title: form.title,
    coverImage: form.coverImage,
    price: form.price,
    duration: form.duration,
  })
  // 清空旧错误后按本次校验结果回填（字段下方内联展示）
  for (const key of Object.keys(fieldErrors)) {
    delete fieldErrors[key]
  }
  if (!parsed.success) {
    for (const issue of parsed.error.issues) {
      const key = String(issue.path[0] ?? '')
      if (key && !fieldErrors[key]) {
        fieldErrors[key] = issue.message
      }
    }
    return
  }
  saveBasicMutation()
}
</script>

<template>
  <div class="space-y-5">
    <!-- 编辑模式加载骨架 -->
    <div v-if="isLoading" data-testid="edit-skeleton" class="space-y-5" aria-label="课程加载中">
      <div class="grid grid-cols-2 gap-5 xl:grid-cols-4">
        <div
          v-for="i in 4"
          :key="`stat-${i}`"
          class="h-[118px] animate-pulse rounded-2xl bg-surface-2"
        />
      </div>
      <div class="rounded-2xl border border-border bg-surface">
        <div class="h-14 animate-pulse border-b border-border bg-surface-2" />
        <div class="grid grid-cols-2 gap-6 p-6">
          <div v-for="i in 6" :key="`form-${i}`" class="h-10 animate-pulse rounded bg-surface-2" />
        </div>
      </div>
    </div>

    <!-- 加载错误：横幅 + 重试 -->
    <div
      v-else-if="listError"
      role="alert"
      class="flex items-center justify-between gap-4 rounded-xl border border-danger/30 bg-danger/5 px-4 py-3"
    >
      <span class="text-sm text-danger">{{ listError }}</span>
      <Button variant="outline" size="sm" @click="refetch">重试</Button>
    </div>

    <template v-else>
      <!-- 统计区（编辑态专属）：学习人数/内容板块/排期/教师 四卡，全为真实字段 -->
      <div v-if="statCards.length > 0" class="grid grid-cols-2 gap-[22px] xl:grid-cols-4">
        <div v-for="(s, i) in statCards" :key="s.label" v-reveal="i * 60">
          <StatCard :label="s.label" :value="s.value" :tone="s.tone" count-up>
            <template #icon>
              <PhUsers v-if="s.label === '学习人数'" class="h-[21px] w-[21px]" />
              <PhArticle v-else-if="s.label === '内容板块'" class="h-[21px] w-[21px]" />
              <PhCalendarBlank v-else-if="s.label === '排期数量'" class="h-[21px] w-[21px]" />
              <PhChalkboardTeacher v-else class="h-[21px] w-[21px]" />
            </template>
          </StatCard>
        </div>
      </div>

      <!-- 分区卡一：基本信息（封面上传/标题/分类/简述） -->
      <section v-reveal class="rounded-2xl border border-border bg-surface p-6 shadow-xs">
        <div class="flex flex-wrap items-baseline justify-between gap-2">
          <h2 class="text-lg font-extrabold tracking-tight text-text">基本信息</h2>
          <p v-if="isNew" class="text-xs text-text-subtle">
            保存后将进入完整编辑页，可配置内容、排期与学员
          </p>
        </div>
        <div class="mt-5 grid grid-cols-2 gap-x-6 gap-y-4">
          <!-- 封面：image-upload（点击/拖拽上传 + 预览 + 重传删除，契约 D/F） -->
          <div class="col-span-2">
            <span class="mb-1.5 block text-sm font-medium text-text">封面图</span>
            <ImageUpload v-model="form.coverImage" data-testid="field-cover" />
          </div>

          <!-- 标题*：zod 校验，错误红字字段下方 -->
          <Input
            v-model="form.title"
            data-testid="field-title"
            label="标题"
            required
            :error="fieldErrors.title"
            placeholder="请输入课程标题"
          />

          <!-- 分类：预置选项下拉（datalist 允许输入自定义值） -->
          <div>
            <label for="course-category" class="mb-1.5 block text-sm font-medium text-text"
              >分类</label
            >
            <input
              id="course-category"
              v-model="form.category"
              type="text"
              list="course-category-presets"
              data-testid="field-category"
              aria-label="课程分类"
              placeholder="从预置分类选择或输入自定义值"
              class="h-10 w-full rounded-xl border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
            />
            <datalist id="course-category-presets">
              <option v-for="preset in COURSE_CATEGORY_PRESETS" :key="preset" :value="preset" />
            </datalist>
          </div>

          <!-- 简述 -->
          <Textarea
            v-model="form.description"
            data-testid="field-description"
            label="简述"
            :rows="2"
            class="col-span-2"
            placeholder="一句话介绍课程内容"
          />
        </div>
      </section>

      <!-- 分区卡二：授课与定价（教师多选/讲师/价格/课时/状态） -->
      <section v-reveal="80" class="rounded-2xl border border-border bg-surface p-6 shadow-xs">
        <h2 class="text-lg font-extrabold tracking-tight text-text">授课与定价</h2>
        <div class="mt-5 grid grid-cols-2 gap-x-6 gap-y-4">
          <!-- 授课教师：remote-select 多选（防抖 300ms + 取消，契约 E） -->
          <div class="col-span-2">
            <span class="mb-1.5 block text-sm font-medium text-text">授课教师</span>
            <RemoteSelect
              :model-value="selectedTeachers"
              :get-value="(t: UserDTO) => t.id"
              :get-label="(t: UserDTO) => t.displayName"
              :fetcher="fetchTeachers"
              :initial-options="initialTeachers ?? []"
              multiple
              placeholder="搜索教师姓名/用户名，选中后可继续添加"
              empty-text="没有匹配的教师"
              data-testid="field-teachers"
              @update:model-value="onTeachersChange"
            />
            <p class="mt-1 text-xs text-text-subtle">保存后按增删差集更新课程教师关联</p>
          </div>

          <!-- 讲师名（教师预填第一位，仍可手改） -->
          <Input
            v-model="form.instructorName"
            data-testid="field-instructor"
            label="讲师名"
            placeholder="主讲老师姓名"
          />

          <!-- 价格（数字域 tabular-nums，提交时数值化） -->
          <Input
            v-model="form.price"
            data-testid="field-price"
            label="价格（元）"
            type="number"
            :error="fieldErrors.price"
            placeholder="如 199"
          />

          <!-- 课时（数字校验，允许留空） -->
          <Input
            v-model="form.duration"
            data-testid="field-duration"
            label="课时"
            :error="fieldErrors.duration"
            placeholder="如 8"
          />

          <!-- 状态（仅编辑态；新建默认 ACTIVE 由后端落库） -->
          <Select
            v-if="!isNew"
            v-model="form.status"
            data-testid="field-status"
            label="状态"
            :options="[
              { value: 'ACTIVE', label: 'ACTIVE（上架）' },
              { value: 'ARCHIVED', label: 'ARCHIVED（归档）' },
            ]"
          />
          <div v-else>
            <p class="mb-1.5 text-sm font-medium text-text">状态</p>
            <p class="rounded-xl bg-surface-2 px-3 py-2.5 text-xs text-text-muted">
              新建课程默认 ACTIVE，保存后可调整
            </p>
          </div>
        </div>
      </section>

      <!-- 分区卡三：报名与标签（含保存行） -->
      <section v-reveal="160" class="rounded-2xl border border-border bg-surface p-6 shadow-xs">
        <h2 class="text-lg font-extrabold tracking-tight text-text">报名与标签</h2>
        <div class="mt-5 grid grid-cols-2 gap-x-6 gap-y-4">
          <!-- 报名链接：服务端自动生成（契约 A）——编辑态只读 + 复制；新建态提示 -->
          <div class="col-span-2">
            <span class="mb-1.5 block text-sm font-medium text-text">报名链接</span>
            <!-- 编辑态：只读展示 + 一键复制（对勾 1.5s 复位） -->
            <div
              v-if="!isNew"
              class="flex items-center gap-2 rounded-xl border border-border bg-surface-2 px-3 py-2"
              data-testid="enrollment-link-box"
            >
              <p
                data-testid="field-enrollment-link"
                class="min-w-0 flex-1 truncate text-sm text-text-muted"
                :title="courseData?.enrollmentLink ?? ''"
              >
                {{ courseData?.enrollmentLink || '暂未生成' }}
              </p>
              <Button
                v-if="courseData?.enrollmentLink"
                variant="outline"
                size="sm"
                data-testid="copy-link"
                :disabled="linkCopied"
                @click="copyEnrollmentLink"
              >
                <PhCheck v-if="linkCopied" class="h-3.5 w-3.5 text-success" />
                <PhCopy v-else class="h-3.5 w-3.5" />
                {{ linkCopied ? '已复制' : '复制' }}
              </Button>
            </div>
            <!-- 新建态：不出输入框，占位提示保存后生成 -->
            <p
              v-else
              data-testid="enrollment-link-hint"
              class="rounded-xl bg-surface-2 px-3 py-2.5 text-xs text-text-muted"
            >
              保存后自动生成
            </p>
          </div>

          <!-- 标签 chips：回车添加 + X 删除 -->
          <div class="col-span-2">
            <label for="course-tags" class="mb-1.5 block text-sm font-medium text-text">标签</label>
            <div
              class="flex min-h-10 flex-wrap items-center gap-2 rounded-xl border border-border bg-surface px-3 py-1.5 transition-colors duration-150 focus-within:border-brand focus-within:ring-2 focus-within:ring-brand/20"
            >
              <span
                v-for="tag in tags"
                :key="tag"
                :data-testid="`tag-chip-${tag}`"
                class="inline-flex items-center gap-1 rounded-full bg-brand-soft px-2.5 py-0.5 text-xs font-medium text-brand-strong"
              >
                {{ tag }}
                <button
                  type="button"
                  :data-testid="`tag-remove-${tag}`"
                  aria-label="移除标签"
                  class="text-brand-strong/60 transition-colors duration-150 hover:text-danger"
                  @click="removeTag(tag)"
                >
                  <PhX class="h-3 w-3" weight="bold" />
                </button>
              </span>
              <input
                id="course-tags"
                v-model="tagInput"
                type="text"
                data-testid="tag-input"
                aria-label="标签输入"
                placeholder="输入后回车添加"
                class="h-7 min-w-[120px] flex-1 bg-transparent text-sm text-text outline-none placeholder:text-text-subtle"
                @keydown.enter.prevent="addTag"
              />
            </div>
          </div>
        </div>

        <!-- 保存行：新建 create / 编辑 update（提交期禁用 + spinner） -->
        <div class="mt-6 flex justify-end border-t border-border pt-4">
          <Button data-testid="save-basic" :disabled="saving" @click="saveBasic">
            <PhSpinnerGap v-if="saving" class="h-4 w-4 animate-spin" />
            {{ saving ? (isNew ? '创建中' : '保存中') : isNew ? '创建课程' : '保存基础信息' }}
          </Button>
        </div>
      </section>
    </template>
  </div>
</template>
