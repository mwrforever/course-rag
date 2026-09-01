<script setup lang="ts">
/**
 * 封面上传组件（契约 F：点击/拖拽上传 + 预览 + 校验 + 重试 + 删除）
 *
 * 职责：通用图片上传投递区。点击或拖拽触发同一「校验 → multipart 上传」链路；
 * 校验失败（类型/超限）内联红字不发请求（镜像后端白名单前置拦截）；
 * 上传成功回传 data.url（相对路径直作 <img src>）；上传失败保留原图/占位并给重试钮；
 * 右上角 X 确认后回传 null（仅清表单值，MinIO 旧对象不删，见契约 D.2.5）。
 *
 * 上传走 lib/api 的 apiClient（统一解包 + 401 单飞刷新），禁止绕过。
 * 线程安全注意：全部状态为组件私有 ref，无跨实例共享可变状态。
 */
import { ref } from 'vue'
import { PhSpinnerGap, PhUploadSimple, PhWarning, PhX } from '@phosphor-icons/vue'

import { ApiError, UPLOAD_TIMEOUT_MS, apiClient } from '@/lib/api'
import { COVER_ALLOWED_EXTENSIONS, COVER_MAX_SIZE_MB } from '@/lib/constants'
import { cn } from '@/lib/utils'
import type { CourseCoverVO } from '@/lib/types'

const props = withDefaults(
  defineProps<{
    /** 当前值：已上传封面的可访问 URL（契约 D 的 data.url；空串/null = 未设置） */
    modelValue: string | null
    /** 接受的文件类型（input accept 属性，缺省契约 D 白名单） */
    accept?: string
    /** 大小上限（MB，缺省契约 D course.cover.max-size-mb 同值 5） */
    maxSizeMb?: number
    /** 上传端点（相对 /api/v1 的路径，缺省课程封面上传端点） */
    uploadUrl?: string
    /** 是否禁用（表单提交中锁定） */
    disabled?: boolean
  }>(),
  {
    accept: `.${COVER_ALLOWED_EXTENSIONS.join(',.')}`,
    maxSizeMb: COVER_MAX_SIZE_MB,
    uploadUrl: '/admin/courses/cover',
    disabled: false,
  },
)

const emit = defineEmits<{
  /** 上传成功回传 data.url；删除回传 null */
  'update:modelValue': [value: string | null]
  /** 校验/上传失败消息（表单层内联展示/toast 决策由页面定） */
  error: [message: string]
}>()

/** 上传中（不确定进度环 + 禁止重复选择） */
const uploading = ref(false)
/** 内联错误文案（校验失败或上传失败；空串 = 无错误） */
const errorMessage = ref('')
/** 最近一次校验通过待传/已传文件（失败重试按钮重传同一文件） */
const lastFile = ref<File | null>(null)
/** 拖拽悬浮高亮标记 */
const dragging = ref(false)
/** 隐藏文件输入引用（投递区点击唤起） */
const fileInputRef = ref<HTMLInputElement | null>(null)

/**
 * 前置校验（镜像后端白名单：扩展名 + 大小上限）
 *
 * @param file 待校验文件
 * @returns 空串表示合法；否则中文错误文案（内联展示，不发网络请求）
 */
function validateFile(file: File): string {
  const dot = file.name.lastIndexOf('.')
  const ext = dot >= 0 ? file.name.slice(dot + 1).toLowerCase() : ''
  if (!(COVER_ALLOWED_EXTENSIONS as readonly string[]).includes(ext)) {
    return `仅支持 ${COVER_ALLOWED_EXTENSIONS.join('/')} 格式图片`
  }
  if (file.size > props.maxSizeMb * 1024 * 1024) {
    return `图片大小超过 ${props.maxSizeMb}MB 上限`
  }
  return ''
}

/**
 * 校验并上传文件（点击选择与拖拽共用同一链路）
 *
 * @param file 待上传文件（null 安全忽略）
 */
async function uploadFile(file: File | null) {
  if (!file || uploading.value || props.disabled) return
  const invalid = validateFile(file)
  if (invalid) {
    errorMessage.value = invalid
    emit('error', invalid)
    return
  }
  lastFile.value = file
  uploading.value = true
  errorMessage.value = ''
  try {
    // multipart 字段名 file（契约 D.2.2）；经 apiClient 统一解包拿业务数据；
    // 上传类请求 per-request 放宽超时（BUG-03：慢网络下 5MB 也会超实例级 20s）
    const form = new FormData()
    form.set('file', file)
    const response = await apiClient.post<CourseCoverVO>(props.uploadUrl, form, {
      timeout: UPLOAD_TIMEOUT_MS,
    })
    emit('update:modelValue', response.data.url)
  } catch (err) {
    // 上传失败：保留原图/占位态，错误内联 + 重试入口（不抛出，页面经 error 事件感知）
    const message = err instanceof ApiError ? err.message : '封面上传失败，请稍后重试'
    errorMessage.value = message
    emit('error', message)
  } finally {
    uploading.value = false
  }
}

/** 重试：重传最近一次校验通过的文件 */
function retryUpload() {
  void uploadFile(lastFile.value)
}

/** 移除封面：确认后回传 null（仅清表单值，MinIO 旧对象不删） */
function removeImage() {
  if (props.disabled) return
  if (!window.confirm('确认移除封面图片？')) return
  errorMessage.value = ''
  emit('update:modelValue', null)
}

/** 投递区点击：唤起文件选择（上传中禁止重复选择） */
function openPicker() {
  if (uploading.value || props.disabled) return
  fileInputRef.value?.click()
}

/** 文件选择变化：走统一上传链路后清空 input 值（同名文件可重复选择） */
function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  void uploadFile(file)
  input.value = ''
}

/** 拖拽落下：取首个文件走统一上传链路 */
function onDrop(event: DragEvent) {
  dragging.value = false
  const file = event.dataTransfer?.files?.[0] ?? null
  void uploadFile(file)
}
</script>

<template>
  <div data-testid="image-upload" class="w-full max-w-[520px]">
    <!-- 投递区（已有值时为预览替换区）：button 角色可键盘触发，拖拽悬浮高亮 -->
    <div
      role="button"
      tabindex="0"
      aria-label="上传封面图片"
      data-testid="upload-dropzone"
      :class="
        cn(
          'relative flex cursor-pointer flex-col items-center justify-center rounded-xl border-2 border-dashed px-4 text-center transition-colors duration-150 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand',
          dragging
            ? 'border-brand bg-brand-light'
            : 'border-border bg-brand-light/60 hover:border-brand/50',
          props.modelValue ? 'h-44' : 'gap-2 py-8',
          props.disabled && 'cursor-not-allowed opacity-60',
        )
      "
      @click="openPicker"
      @keydown.enter.prevent="openPicker"
      @dragover.prevent="dragging = true"
      @dragleave="dragging = false"
      @drop.prevent="onDrop"
    >
      <!-- 已有值：预览替换（点击/拖入新图替换；上传失败保留原图） -->
      <template v-if="props.modelValue">
        <img
          :src="props.modelValue"
          alt="封面预览"
          data-testid="upload-preview"
          class="h-full w-full rounded-[10px] object-cover"
        />
        <!-- 右上角移除钮（独立 aria-label，X 图标） -->
        <button
          type="button"
          aria-label="移除封面"
          data-testid="upload-remove"
          :disabled="props.disabled"
          class="absolute top-2 right-2 grid h-7 w-7 place-items-center rounded-full bg-ink-900/70 text-white transition-colors duration-150 hover:bg-danger focus-visible:outline-2 focus-visible:outline-brand"
          @click.stop="removeImage"
        >
          <PhX class="h-3.5 w-3.5" weight="bold" />
        </button>
      </template>

      <!-- 空态：虚线投递区 + 图标引导（禁 emoji） -->
      <template v-else>
        <span class="grid h-12 w-12 place-items-center rounded-full bg-surface shadow-xs">
          <PhUploadSimple class="h-6 w-6 text-brand" />
        </span>
        <p class="mt-3 text-sm font-medium text-text">点击或拖拽上传封面</p>
        <p class="mt-1 text-xs text-text-subtle">
          支持 {{ COVER_ALLOWED_EXTENSIONS.join('/') }}，≤{{ props.maxSizeMb }}MB
        </p>
      </template>

      <!-- 上传中遮罩：不确定进度环 + 文案（后端无进度事件，与 C 端附件 chip 同范式） -->
      <div
        v-if="uploading"
        data-testid="upload-progress"
        class="absolute inset-0 flex flex-col items-center justify-center gap-2 rounded-[10px] bg-overlay"
      >
        <PhSpinnerGap class="h-7 w-7 animate-spin text-white" />
        <p class="text-xs font-medium text-white">上传中</p>
      </div>
    </div>

    <!-- 内联错误：红字 + Warning 图标（校验失败/上传失败共用）；失败附重试钮 -->
    <div v-if="errorMessage" data-testid="upload-error" class="mt-1.5 flex items-center gap-2">
      <p class="flex items-center gap-1 text-xs text-danger">
        <PhWarning class="h-3.5 w-3.5 shrink-0" />
        {{ errorMessage }}
      </p>
      <!-- 重试：重传同一文件（仅上传失败后文件在场景有意义） -->
      <button
        v-if="lastFile && !uploading"
        type="button"
        data-testid="upload-retry"
        class="rounded-md px-1.5 py-0.5 text-xs font-medium text-brand transition-colors duration-150 hover:bg-brand-soft focus-visible:outline-2 focus-visible:outline-brand"
        @click="retryUpload"
      >
        重试
      </button>
    </div>

    <!-- 隐藏文件输入：accept 白名单由 props 驱动 -->
    <input
      ref="fileInputRef"
      type="file"
      :accept="props.accept"
      class="hidden"
      data-testid="upload-input"
      @change="onFileChange"
    />
  </div>
</template>
