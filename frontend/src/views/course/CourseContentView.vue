<script setup lang="ts">
/**
 * 课程内容（UI 重构 2026-08-25 从 CourseEditView 拆出）
 *
 * 职责：intro/syllabus/instructor/faq 四个 md-editor-v3 Tab，按后端 sortOrder 排序，
 * 逐 Tab 独立保存（PUT /contents/{contentType} 裸 JSON 字符串 body）。
 */
import { computed, ref, watch } from 'vue'
import { useMutation, useQuery } from '@tanstack/vue-query'
import { useRoute } from 'vue-router'
import { MdEditor } from 'md-editor-v3'
import 'md-editor-v3/lib/style.css'
import { PhSpinnerGap } from '@phosphor-icons/vue'

import { Button } from '@/components/ui/button'
import { ApiError, courseApi } from '@/lib/api'
import { showToast } from '@/lib/toast'

const route = useRoute()
/** 课程 id（Long 字符串铁律） */
const courseId = computed(() => String(route.params.id ?? ''))

/** 四 Tab 常量表：type 与后端 contentType 一致，label 为界面文案 */
const CANONICAL_TABS: { type: string; label: string; sort: number }[] = [
  { type: 'intro', label: '课程介绍', sort: 1 },
  { type: 'syllabus', label: '教学大纲', sort: 2 },
  { type: 'instructor', label: '讲师信息', sort: 3 },
  { type: 'faq', label: '常见问题', sort: 4 },
]

/** 各 Tab 独立保存成功 toast（逐 Tab 独立保存，文案区分） */
const CONTENT_SAVED_TOAST: Record<string, string> = {
  intro: '课程介绍已保存',
  syllabus: '教学大纲已保存',
  instructor: '讲师信息已保存',
  faq: '常见问题已保存',
}

/** Tab 渲染顺序：按后端 sortOrder；后端缺失时回退常量表 */
const tabOrder = ref<{ type: string; label: string }[]>([])
/** 正文缓存：contentType → markdown 正文（Tab 切换互不串写；编辑器本地状态不进查询缓存） */
const contentMap = ref<Record<string, string>>({})
/** 当前激活 Tab */
const activeTab = ref('intro')

/** 内容加载（查询键含路由 id；加载结果回写编辑器本地状态） */
const {
  data: contentsData,
  isLoading: contentsLoading,
  isError: contentsIsError,
  error: contentsQueryError,
  refetch,
} = useQuery({
  queryKey: computed(() => ['course-contents', courseId.value]),
  queryFn: () => courseApi.contents(courseId.value),
  // 编辑器本地状态（contentMap）不随后台 refetch 覆盖：禁用窗口聚焦重拉
  refetchOnWindowFocus: false,
})

/** 内容加载失败横幅文案（queryError 非空时透出；503 统一降级） */
const contentsError = computed(() =>
  contentsIsError.value ? messageOf(contentsQueryError.value, '内容加载失败，请稍后重试') : '',
)

/** 加载完成回写编辑器本地状态：按 sortOrder 排序建索引（缺失 body 兜底空串） */
watch(contentsData, (list) => {
  if (!list) return
  const map: Record<string, string> = {}
  for (const item of list) {
    map[item.contentType] = item.content ?? ''
  }
  contentMap.value = map
  tabOrder.value =
    list.length > 0
      ? list
          .slice()
          .sort((a, b) => a.sortOrder - b.sortOrder)
          .map((item) => ({ type: item.contentType, label: labelOf(item.contentType) }))
      : CANONICAL_TABS.map((t) => ({ type: t.type, label: t.label }))
  activeTab.value = tabOrder.value[0]?.type ?? 'intro'
})

/** 当前激活 Tab 的正文（编辑器 modelValue 输入源） */
const activeContent = computed(() => contentMap.value[activeTab.value] ?? '')

function messageOf(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    return err.code === 503 ? '服务暂时不可用，请稍后重试' : err.message
  }
  return fallback
}

/** contentType → Tab 文案（未知类型以原值兜底展示） */
function labelOf(type: string): string {
  return CANONICAL_TABS.find((t) => t.type === type)?.label ?? type
}

/** 编辑器输入回写当前 Tab（正文缓存按 contentType 分键，切 Tab 不丢未保存内容） */
function handleContentEdit(value: string) {
  contentMap.value[activeTab.value] = value
}

/**
 * 逐 Tab 独立保存提交（PUT /{id}/contents/{contentType}，body 为裸 JSON 字符串；
 * api 层显式 Content-Type: application/json，axios 字符串 data 原样透传）
 */
const { isPending: contentSaving, mutate: saveContentMutation } = useMutation({
  mutationFn: (type: string) =>
    courseApi.updateContent(courseId.value, type, contentMap.value[type] ?? ''),
  onSuccess: (_res, type) => {
    showToast(CONTENT_SAVED_TOAST[type] ?? '内容已保存', 'success')
  },
  onError: (err) => {
    showToast(messageOf(err, '内容保存失败，请稍后重试'), 'danger')
  },
})

/** 逐 Tab 独立保存：加载失败态拦截 → 走 mutation */
function saveContent() {
  if (contentsError.value) return
  saveContentMutation(activeTab.value)
}
</script>

<template>
  <section class="overflow-hidden rounded-xl border border-border bg-surface">
    <div class="flex items-center justify-between border-b border-border px-6 py-4">
      <h2 class="text-base font-semibold text-text">课程内容</h2>
      <p class="text-xs text-text-subtle">四个 Tab 独立保存，互不影响</p>
    </div>

    <!-- 内容区加载中：与编辑器同形的灰块 -->
    <div v-if="contentsLoading" class="p-6">
      <div class="h-12 animate-pulse rounded bg-surface-2" />
      <div class="mt-4 h-64 animate-pulse rounded bg-surface-2" />
    </div>

    <!-- 内容区错误态：横幅 + 重试 -->
    <div v-else-if="contentsError" data-testid="contents-error" class="p-6">
      <div
        class="flex items-center justify-between gap-4 rounded-xl border border-danger/30 bg-danger/5 px-4 py-3"
      >
        <span class="text-sm text-danger">{{ contentsError }}</span>
        <Button variant="outline" size="sm" data-testid="retry-contents" @click="refetch">
          重试
        </Button>
      </div>
    </div>

    <template v-else>
      <!-- Tab 切换条：激活态 brand 下划线 -->
      <div class="flex gap-1 border-b border-border px-6 pt-3">
        <button
          v-for="tab in tabOrder"
          :key="tab.type"
          type="button"
          :data-testid="`tab-${tab.type}`"
          class="-mb-px border-b-2 px-4 py-2.5 text-sm transition-colors duration-150"
          :class="
            activeTab === tab.type
              ? 'border-brand font-medium text-brand-strong'
              : 'border-transparent text-text-muted hover:text-text'
          "
          @click="activeTab = tab.type"
        >
          {{ tab.label }}
        </button>
      </div>

      <!-- md-editor-v3 编辑器：绑定当前 Tab 正文（onChange/update:modelValue 双通道回写） -->
      <div class="p-6">
        <MdEditor
          :model-value="activeContent"
          :style="{ height: '420px' }"
          @update:model-value="handleContentEdit"
          @on-change="handleContentEdit"
        />
      </div>

      <!-- 保存行：仅保存当前 Tab（PUT 裸 JSON 字符串 body） -->
      <div
        class="flex items-center justify-end gap-3 border-t border-border bg-surface-2 px-6 py-3"
      >
        <span class="text-xs text-text-subtle">当前保存：{{ labelOf(activeTab) }}</span>
        <Button data-testid="save-content" :disabled="contentSaving" @click="saveContent">
          <PhSpinnerGap v-if="contentSaving" class="h-4 w-4 animate-spin" />
          {{ contentSaving ? '保存中' : '保存本页内容' }}
        </Button>
      </div>
    </template>
  </section>
</template>
