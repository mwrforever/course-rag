<script setup lang="ts">
/**
 * 课程详情壳（UI 重构 2026-08-25：1635 行 CourseEditView 按职责拆分）
 *
 * 职责：课程元数据加载（标题/存在性校验 404）+ 页头（返回列表 + 课程标题）+
 * 子导航（概览/内容/排期/教师/学生，RouterLink 激活态）+ 子路由出口。
 * 各子视图（Overview/Content/Schedule/Teachers/Students）自行加载领域数据，互不耦合。
 */
import { computed, onMounted, ref } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { PhArrowLeft, PhSpinnerGap } from '@phosphor-icons/vue'

import { Button } from '@/components/ui/button'
import { ApiError, courseApi } from '@/lib/api'
import type { CourseDTO } from '@/lib/types'

const route = useRoute()
const router = useRouter()

/** 课程 id（Long 字符串铁律） */
const courseId = computed(() => String(route.params.id ?? ''))

/** 课程元数据（标题 + 存在性） */
const course = ref<CourseDTO | null>(null)
const loading = ref(true)
const error = ref('')
const notFound = ref(false)

function messageOf(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    return err.code === 503 ? '服务暂时不可用，请稍后重试' : err.message
  }
  return fallback
}

/** 加载课程元数据：404 → 课程不存在态；其余错误 → 横幅重试 */
async function loadCourse() {
  loading.value = true
  error.value = ''
  notFound.value = false
  try {
    course.value = await courseApi.get(courseId.value)
  } catch (err) {
    if (err instanceof ApiError && err.code === 404) {
      notFound.value = true
    } else {
      error.value = messageOf(err, '课程加载失败，请稍后重试')
    }
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void loadCourse()
})

function goBackToList() {
  router.push({ name: 'courses' })
}

/** 子导航：路由名 ↔ 文案（激活态由路由名精确匹配） */
const SECTION_NAV: { name: string; label: string }[] = [
  { name: 'course-detail', label: '概览' },
  { name: 'course-content', label: '内容' },
  { name: 'course-schedule', label: '排期' },
  { name: 'course-teachers', label: '教师' },
  { name: 'course-students', label: '学生' },
]
</script>

<template>
  <div>
    <!-- 页头：返回列表 + 课程标题 -->
    <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
      <Button variant="ghost" size="sm" data-testid="back-to-courses" @click="goBackToList">
        <PhArrowLeft class="h-4 w-4" />
        返回课程列表
      </Button>
      <p
        v-if="course"
        class="text-sm font-medium text-text-muted"
        data-testid="course-detail-title"
      >
        {{ course.title }}
      </p>
    </div>

    <!-- 加载骨架：与子导航同形的灰块 -->
    <div v-if="loading" data-testid="course-detail-skeleton" class="animate-pulse space-y-4">
      <div class="h-12 rounded-xl bg-surface-2" />
      <div class="h-64 rounded-xl bg-surface-2" />
    </div>

    <!-- 404：课程不存在或已下架 -->
    <div
      v-else-if="notFound"
      class="flex flex-col items-center gap-3 rounded-xl border border-border bg-surface py-16 text-center"
    >
      <p class="text-sm text-text-muted">课程不存在或已下架</p>
      <Button variant="outline" size="sm" @click="goBackToList">返回课程列表</Button>
    </div>

    <!-- 加载错误：横幅 + 重试 -->
    <div
      v-else-if="error"
      role="alert"
      class="flex items-center justify-between gap-4 rounded-xl border border-danger/30 bg-danger/5 px-4 py-3"
    >
      <span class="text-sm text-danger">{{ error }}</span>
      <Button variant="outline" size="sm" data-testid="retry-course" @click="loadCourse">
        <PhSpinnerGap v-if="loading" class="h-4 w-4 animate-spin" />
        重试
      </Button>
    </div>

    <!-- 正常态：子导航 + 子路由出口 -->
    <template v-else>
      <nav
        aria-label="课程编辑分区"
        class="mb-5 flex gap-1 overflow-x-auto rounded-xl border border-border bg-surface p-1"
      >
        <RouterLink
          v-for="section in SECTION_NAV"
          :key="section.name"
          :to="{ name: section.name, params: { id: courseId } }"
          class="shrink-0 rounded-lg px-4 py-2 text-sm transition-colors duration-150"
          :class="
            route.name === section.name
              ? 'bg-brand-soft font-medium text-brand-strong'
              : 'text-text-muted hover:bg-surface-2 hover:text-text'
          "
          :data-testid="`course-nav-${section.name}`"
        >
          {{ section.label }}
        </RouterLink>
      </nav>

      <RouterView />
    </template>
  </div>
</template>
