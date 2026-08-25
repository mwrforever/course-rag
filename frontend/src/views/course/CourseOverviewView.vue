<script setup lang="ts">
/**
 * 课程概览（基础信息，UI 重构 2026-08-25 从 CourseEditView 拆出）
 *
 * 职责：封面 URL + 实时预览 / 标题* zod 前置校验 / 简述 / 分类 / 讲师名 / 价格 /
 * 课时 / 标签 chips / 报名链接 / 状态。新建模式（/courses/new 独立路由）create 后
 * 跳转详情；编辑模式（/courses/:id 概览子路由）update 保存。
 */
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { z } from 'zod'
import { PhImageSquare, PhSpinnerGap, PhX } from '@phosphor-icons/vue'

import { Button } from '@/components/ui/button'
import { ApiError, courseApi } from '@/lib/api'
import { showToast } from '@/lib/toast'
import type { CourseDTO, UpdateCourseRequest } from '@/lib/types'

/** 标题必填校验（设计 §2.4.4：标题*，错误红字 input 下方） */
const titleSchema = z.object({ title: z.string().min(1, '请输入课程标题') })

/** 基础表单承载：价格以字符串承载，提交时数值化（避免输入过程 Number 精度抖动） */
const form = reactive({
  title: '',
  description: '',
  coverImage: '',
  category: '',
  instructorName: '',
  price: '',
  duration: '',
  enrollmentLink: '',
  status: 'ACTIVE' as 'ACTIVE' | 'ARCHIVED',
})

/** 标签 chips：回车添加、X 删除（提交体为字符串数组） */
const tags = ref<string[]>([])
const tagInput = ref('')
/** 标题就地错误（zod 校验失败展示，校验通过清空） */
const fieldError = ref('')
/** 基础信息保存中（提交期按钮禁用 + 文案切换） */
const saving = ref(false)
/** 封面图加载失败标记：onError 兜底切占位（无上传接口 G11），URL 变更自动复位 */
const coverBroken = ref(false)
/** 编辑模式页面级四态 */
const loading = ref(false)
const error = ref('')

const route = useRoute()
const router = useRouter()

/** 新建模式：路由名 course-new；编辑模式 course-detail（带 :id） */
const isNew = route.name === 'course-new'
/** 课程 id：编辑模式取自路由参数（Long 字符串铁律） */
const courseId = (): string => String(route.params.id ?? '')

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

/** 课程回填基础表单（价格数值 → 字符串承载；标签数组化；封面错误态复位） */
function applyCourseToForm(c: CourseDTO) {
  form.title = c.title
  form.description = c.description ?? ''
  form.coverImage = c.coverImage ?? ''
  form.category = c.category ?? ''
  form.instructorName = c.instructorName ?? ''
  form.price = c.price === 0 ? '' : String(c.price)
  form.duration = c.duration ?? ''
  form.enrollmentLink = c.enrollmentLink ?? ''
  form.status = c.status === 'ARCHIVED' ? 'ARCHIVED' : 'ACTIVE'
  tags.value = [...(c.tags ?? [])]
  coverBroken.value = false
}

/** 编辑模式加载课程回填表单 */
async function loadCourse() {
  loading.value = true
  error.value = ''
  try {
    applyCourseToForm(await courseApi.get(courseId()))
  } catch (err) {
    error.value = messageOf(err, '课程加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

/** 封面 URL 变更：错误预览态复位 */
watch(
  () => form.coverImage,
  () => {
    coverBroken.value = false
  },
)

onMounted(() => {
  if (!isNew) {
    void loadCourse()
  }
})

/**
 * 基础信息保存：zod 前置校验（失败就地报错不发请求）→ 新建/编辑分派
 *
 * 新建：create（CreateCourseRequest 不含 status，新建默认 ACTIVE）→ toast →
 * 跳转 /courses/{id} 继续编辑；编辑：update（UpdateCourseRequest 全字段含 status）。
 */
async function saveBasic() {
  const parsed = titleSchema.safeParse({ title: form.title })
  if (!parsed.success) {
    fieldError.value = parsed.error.issues[0]?.message ?? '请输入课程标题'
    return
  }
  fieldError.value = ''
  saving.value = true
  try {
    const common = {
      title: form.title,
      description: form.description,
      coverImage: form.coverImage,
      category: form.category,
      instructorName: form.instructorName,
      price: form.price === '' ? undefined : Number(form.price),
      duration: form.duration,
      tags: tags.value.length > 0 ? tags.value : null,
      enrollmentLink: form.enrollmentLink,
    }
    if (isNew) {
      const created = await courseApi.create(common)
      showToast('课程创建成功', 'success')
      await router.push({ name: 'course-detail', params: { id: created.id } })
    } else {
      const payload: UpdateCourseRequest = { ...common, status: form.status }
      await courseApi.update(courseId(), payload)
      showToast('课程信息已保存', 'success')
    }
  } catch (err) {
    showToast(messageOf(err, '保存失败，请稍后重试'), 'danger')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div>
    <!-- 编辑模式加载骨架 -->
    <div v-if="loading" data-testid="edit-skeleton" class="space-y-6" aria-label="课程加载中">
      <div class="rounded-xl border border-border bg-surface">
        <div class="h-14 animate-pulse border-b border-border bg-surface-2" />
        <div class="grid grid-cols-2 gap-6 p-6">
          <div v-for="i in 6" :key="`form-${i}`" class="h-10 animate-pulse rounded bg-surface-2" />
        </div>
      </div>
    </div>

    <!-- 加载错误：横幅 + 重试 -->
    <div
      v-else-if="error"
      role="alert"
      class="flex items-center justify-between gap-4 rounded-xl border border-danger/30 bg-danger/5 px-4 py-3"
    >
      <span class="text-sm text-danger">{{ error }}</span>
      <Button variant="outline" size="sm" @click="loadCourse">重试</Button>
    </div>

    <!-- 基础信息表单 -->
    <section v-else class="rounded-xl border border-border bg-surface p-6">
      <h2 class="text-base font-semibold text-text">基础信息</h2>
      <p v-if="isNew" class="mt-1 text-xs text-text-subtle">
        保存后将进入完整编辑页，可配置内容、排期与学员
      </p>
      <div class="mt-5 grid grid-cols-2 gap-x-6 gap-y-4">
        <!-- 封面 URL + 实时预览（无上传接口 G11，onError 兜底占位） -->
        <div class="col-span-2">
          <label for="course-cover-url" class="mb-1.5 block text-sm font-medium text-text">
            封面图 URL
          </label>
          <div class="flex items-start gap-4">
            <input
              id="course-cover-url"
              v-model="form.coverImage"
              type="text"
              data-testid="field-cover"
              aria-label="封面图 URL"
              placeholder="https://cdn.example.com/cover.jpg（无上传接口，直接填图片地址）"
              class="h-10 w-full max-w-[520px] rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
            />
            <img
              v-if="form.coverImage && !coverBroken"
              :key="form.coverImage"
              data-testid="cover-preview"
              :src="form.coverImage"
              alt="封面预览"
              class="h-16 w-28 shrink-0 rounded-lg border border-border bg-surface-2 object-cover"
              @error="coverBroken = true"
            />
            <div
              v-else-if="form.coverImage && coverBroken"
              data-testid="cover-fallback"
              class="flex h-16 w-28 shrink-0 flex-col items-center justify-center gap-1 rounded-lg border border-border bg-surface-2 text-text-subtle"
            >
              <PhImageSquare class="h-5 w-5" />
              <span class="text-xs">封面预览</span>
            </div>
          </div>
        </div>

        <!-- 标题*：zod 前置校验，错误红字 input 下方 -->
        <div>
          <label for="course-title" class="mb-1.5 block text-sm font-medium text-text">
            标题 <span class="text-danger">*</span>
          </label>
          <input
            id="course-title"
            v-model="form.title"
            type="text"
            data-testid="field-title"
            aria-label="课程标题"
            placeholder="请输入课程标题"
            class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
          />
          <p v-if="fieldError" data-testid="field-error" class="mt-1 text-xs text-danger">
            {{ fieldError }}
          </p>
        </div>

        <!-- 分类 -->
        <div>
          <label for="course-category" class="mb-1.5 block text-sm font-medium text-text"
            >分类</label
          >
          <input
            id="course-category"
            v-model="form.category"
            type="text"
            data-testid="field-category"
            aria-label="课程分类"
            placeholder="如 AI / LLM / RAG"
            class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
          />
        </div>

        <!-- 简述 -->
        <div class="col-span-2">
          <label for="course-description" class="mb-1.5 block text-sm font-medium text-text"
            >简述</label
          >
          <textarea
            id="course-description"
            v-model="form.description"
            rows="2"
            data-testid="field-description"
            aria-label="课程简述"
            placeholder="一句话介绍课程内容"
            class="w-full resize-none rounded-lg border border-border bg-surface px-3 py-2 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
          />
        </div>

        <!-- 讲师名 -->
        <div>
          <label for="course-instructor" class="mb-1.5 block text-sm font-medium text-text"
            >讲师名</label
          >
          <input
            id="course-instructor"
            v-model="form.instructorName"
            type="text"
            data-testid="field-instructor"
            aria-label="讲师名"
            placeholder="主讲老师姓名"
            class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
          />
        </div>

        <!-- 价格（数字域 tabular-nums，提交时数值化） -->
        <div>
          <label for="course-price" class="mb-1.5 block text-sm font-medium text-text"
            >价格（元）</label
          >
          <input
            id="course-price"
            v-model="form.price"
            type="number"
            min="0"
            step="0.01"
            data-testid="field-price"
            aria-label="课程价格"
            placeholder="如 199"
            class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm tabular-nums text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
          />
        </div>

        <!-- 课时 -->
        <div>
          <label for="course-duration" class="mb-1.5 block text-sm font-medium text-text"
            >课时</label
          >
          <input
            id="course-duration"
            v-model="form.duration"
            type="text"
            data-testid="field-duration"
            aria-label="课时"
            placeholder="如 8 课时"
            class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
          />
        </div>

        <!-- 状态（仅编辑态；新建默认 ACTIVE 由后端落库） -->
        <div v-if="!isNew">
          <label for="course-status" class="mb-1.5 block text-sm font-medium text-text">状态</label>
          <select
            id="course-status"
            v-model="form.status"
            data-testid="field-status"
            aria-label="课程状态"
            class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 focus:border-brand focus:ring-2 focus:ring-brand/20"
          >
            <option value="ACTIVE">ACTIVE（上架）</option>
            <option value="ARCHIVED">ARCHIVED（归档）</option>
          </select>
        </div>
        <div v-else>
          <p class="mb-1.5 text-sm font-medium text-text">状态</p>
          <p class="rounded-lg bg-surface-2 px-3 py-2.5 text-xs text-text-muted">
            新建课程默认 ACTIVE，保存后可调整
          </p>
        </div>

        <!-- 报名链接 -->
        <div class="col-span-2">
          <label for="course-link" class="mb-1.5 block text-sm font-medium text-text"
            >报名链接</label
          >
          <input
            id="course-link"
            v-model="form.enrollmentLink"
            type="text"
            data-testid="field-enrollment-link"
            aria-label="报名链接"
            placeholder="https://apply.example.com/xxx（可选）"
            class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
          />
        </div>

        <!-- 标签 chips：回车添加 + X 删除 -->
        <div class="col-span-2">
          <label for="course-tags" class="mb-1.5 block text-sm font-medium text-text">标签</label>
          <div
            class="flex min-h-10 flex-wrap items-center gap-2 rounded-lg border border-border bg-surface px-3 py-1.5"
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
  </div>
</template>
