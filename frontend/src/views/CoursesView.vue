<script setup lang="ts">
/**
 * 课程列表页（设计 §2.4.4 课程列表 + §2.6 表格/分页规范）
 *
 * 能力清单：
 * 1. 分页表格：封面缩略 48px（无封面占位）/ 名称 / 讲师 / 价格 / 课时 /
 *    学生数 / 状态 Badge（ACTIVE emerald / ARCHIVED 中性，设计 §2.5）/ 操作（编辑·删除）
 * 2. 编辑跳转 /courses/{id} 与新建入口 /courses/new（编辑页同一组件复用两路由）
 * 3. 删除：危险操作二次确认（danger 实底 + submitting 拦截 Esc/遮罩）→
 *    DELETE → toast → 刷新（末页清空回退防空页）
 * 4. 分页：左下「共 N 条」+ 右下 上一页/下一页/页码
 * 5. 四态：loading 骨架 / empty（含新建入口）/ error 横幅重试 / 正常
 *
 * 契约要点：id/total/learningCount 为 Long 字符串铁律；价格/课时/人数数字域
 * tabular-nums；教师限己建课程由后端约束，前端不额外过滤。
 *
 * 线程安全注意：全部状态为组件私有 ref，无跨实例共享可变状态。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { PhImageSquare, PhPlus, PhSpinnerGap, PhWarningCircle } from '@phosphor-icons/vue'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ApiError, courseApi } from '@/lib/api'
import { showToast } from '@/lib/toast'

import type { CourseDTO } from '@/lib/types'

/** 每页条数（设计 §2.6 分页器） */
const PAGE_SIZE = 10

/** 列表状态：分页 + 四态 */
const courses = ref<CourseDTO[]>([])
const loading = ref(true)
const error = ref('')
const page = ref(1)
const total = ref('0')

/** 总页数：total 为 Long 字符串，转 number 后按 PAGE_SIZE 上取整（至少 1 页） */
const totalPages = computed(() => Math.max(1, Math.ceil(Number(total.value) / PAGE_SIZE)))

/**
 * 接口错误分级文案（与知识库/分片页 messageOf 同构）
 *
 * @param err 捕获异常：ApiError 透出 message（503 统一降级文案）；未知异常页面兜底
 * @param fallback 非 ApiError 时的操作级兜底文案
 * @returns 展示文案
 */
function messageOf(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    return err.code === 503 ? '服务暂时不可用，请稍后重试' : err.message
  }
  return fallback
}

/**
 * 拉取当前页课程列表（分页参数 page/size）
 *
 * 边界：删除末页最后一条后列表为空且非第一页时回退一页重拉（防空页停留）。
 */
async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await courseApi.list({ page: page.value, size: PAGE_SIZE })
    courses.value = res.records ?? []
    total.value = res.total
    // 删除导致末页清空：回退一页（total 仍大于 0 时递归一次收敛）
    if (courses.value.length === 0 && page.value > 1) {
      page.value -= 1
      await load()
    }
  } catch (err) {
    error.value = messageOf(err, '课程列表加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

onMounted(load)

/** 翻页：越界保护（首页/末页禁用态由 disabled 兜底，方法内再防一次） */
function changePage(next: number) {
  if (next < 1 || next > totalPages.value) return
  page.value = next
  load()
}

const router = useRouter()

/** 编辑跳转：route name course-detail（/courses/:id，由编辑页同一组件承载） */
function goEdit(c: CourseDTO) {
  router.push({ name: 'course-detail', params: { id: c.id } })
}

/** 新建入口：route name course-new（/courses/new，与详情路由复用编辑组件） */
function goCreate() {
  router.push({ name: 'course-new' })
}

/** 状态 Badge：ACTIVE → emerald / ARCHIVED → 中性（设计 §2.5） */
function statusVariant(status: string) {
  return status === 'ACTIVE' ? ('success' as const) : ('default' as const)
}

// ====================================================================
// 删除二次确认（danger 实底 + submitting 拦截，设计 §2.6）
// ====================================================================

/** 待删除课程：非 null 时展示确认 Dialog */
const deleting = ref<CourseDTO | null>(null)
const deletingLoading = ref(false)

function requestDelete(c: CourseDTO) {
  deleting.value = c
}

/** 关闭确认 Dialog：提交期间拦截取消/Esc/遮罩（防误关丢状态） */
function cancelDelete() {
  if (deletingLoading.value) return
  deleting.value = null
}

/** 确认删除：remove → toast → 关闭确认框 → 刷新列表（末页回退见 load 边界） */
async function confirmDelete() {
  if (!deleting.value) return
  deletingLoading.value = true
  try {
    await courseApi.remove(deleting.value.id)
    showToast('课程已删除', 'success')
    deleting.value = null
    await load()
  } catch (err) {
    showToast(messageOf(err, '删除失败，请稍后重试'), 'danger')
  } finally {
    deletingLoading.value = false
  }
}
</script>

<template>
  <!-- 页头操作行：新建入口常驻（列表态/空态共用同一方法） -->
  <div class="mb-4 flex items-center justify-between">
    <p class="text-sm text-text-muted">课程列表：编辑页涵盖内容 4 Tab、排期、教师分配与学生名单</p>
    <Button data-testid="create-course" @click="goCreate">
      <PhPlus class="h-4 w-4" />
      新建课程
    </Button>
  </div>

  <!-- 错误态：页内横幅 + 重试 -->
  <div
    v-if="error"
    role="alert"
    class="flex items-center justify-between gap-4 rounded-lg border border-danger/30 bg-red-50 px-4 py-3"
  >
    <span class="text-sm text-danger">{{ error }}</span>
    <Button variant="outline" size="sm" data-testid="retry-courses" @click="load">重试</Button>
  </div>

  <!-- 加载态：表格骨架屏（与最终表格同形） -->
  <div
    v-else-if="loading"
    data-testid="course-skeleton"
    class="overflow-hidden rounded-xl border border-border bg-surface"
    aria-label="课程列表加载中"
  >
    <div class="flex items-center gap-6 border-b border-border bg-surface-2 px-4 py-2.5">
      <div v-for="i in 7" :key="`head-${i}`" class="h-3 w-20 animate-pulse rounded bg-slate-200" />
    </div>
    <div
      v-for="i in 5"
      :key="`row-${i}`"
      class="h-11 animate-pulse border-b border-border bg-slate-50"
    />
  </div>

  <!-- 空态：一句话 + 新建入口（禁裸「暂无数据」） -->
  <div
    v-else-if="courses.length === 0"
    class="flex flex-col items-center justify-center rounded-xl border border-dashed border-border bg-surface py-14 text-center"
  >
    <PhWarningCircle class="h-8 w-8 text-text-subtle" />
    <p class="mt-3 text-sm font-medium text-text">还没有课程</p>
    <p class="mt-1 text-xs text-text-muted">新建第一个课程后即可配置内容、排期与学员</p>
    <Button class="mt-4" data-testid="create-course-empty" @click="goCreate">新建课程</Button>
  </div>

  <!-- 正常态：分页表格（封面/名称/讲师/价格/课时/学生数/状态/操作） -->
  <template v-else>
    <div class="overflow-hidden rounded-xl border border-border bg-surface">
      <table data-testid="course-table" class="w-full text-sm">
        <thead class="border-b border-border bg-surface-2 text-left text-xs text-text-muted">
          <tr>
            <th class="w-16 px-4 py-2.5 font-medium">封面</th>
            <th class="px-4 py-2.5 font-medium">名称</th>
            <th class="w-28 px-4 py-2.5 font-medium">讲师</th>
            <th class="w-24 px-4 py-2.5 text-right font-medium">价格</th>
            <th class="w-24 px-4 py-2.5 font-medium">课时</th>
            <th class="w-24 px-4 py-2.5 text-right font-medium">学生数</th>
            <th class="w-24 px-4 py-2.5 font-medium">状态</th>
            <th class="w-40 px-4 py-2.5 text-right font-medium">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="c in courses"
            :key="c.id"
            :data-testid="`row-${c.id}`"
            class="h-11 border-b border-border last:border-b-0 transition-colors duration-150 hover:bg-surface-2"
          >
            <!-- 封面缩略 48px：URL 直出；无封面渲染占位（无上传接口 G11） -->
            <td class="px-4">
              <img
                v-if="c.coverImage"
                :data-testid="`cover-${c.id}`"
                :src="c.coverImage"
                :alt="`${c.title} 封面`"
                class="h-12 w-12 rounded-lg border border-border bg-surface-2 object-cover"
              />
              <div
                v-else
                :data-testid="`cover-fallback-${c.id}`"
                class="flex h-12 w-12 items-center justify-center rounded-lg border border-border bg-surface-2"
              >
                <PhImageSquare class="h-5 w-5 text-text-subtle" />
              </div>
            </td>
            <td :data-testid="`course-title-${c.id}`" class="max-w-[240px] px-4">
              <p class="truncate font-medium text-text">{{ c.title }}</p>
            </td>
            <td :data-testid="`course-instructor-${c.id}`" class="px-4 text-text-muted">
              {{ c.instructorName || '未指定' }}
            </td>
            <td class="px-4 text-right">
              <span :data-testid="`course-price-${c.id}`" class="tabular-nums text-text">
                ¥{{ c.price }}
              </span>
            </td>
            <td :data-testid="`course-duration-${c.id}`" class="tabular-nums px-4 text-text-muted">
              {{ c.duration || '未设置' }}
            </td>
            <td class="px-4 text-right">
              <span :data-testid="`course-learners-${c.id}`" class="tabular-nums text-text">
                {{ c.learningCount }}
              </span>
            </td>
            <td class="px-4">
              <Badge :data-testid="`course-status-${c.id}`" :variant="statusVariant(c.status)">
                {{ c.status }}
              </Badge>
            </td>
            <td class="px-4 text-right">
              <div class="flex items-center justify-end gap-1">
                <Button
                  variant="ghost"
                  size="sm"
                  :data-testid="`op-edit-${c.id}`"
                  @click="goEdit(c)"
                >
                  编辑
                </Button>
                <Button
                  variant="danger"
                  size="sm"
                  :data-testid="`op-delete-${c.id}`"
                  @click="requestDelete(c)"
                >
                  删除
                </Button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 分页器：左「共 N 条」右 上/下页 + 页码（设计 §2.6） -->
    <div class="mt-4 flex items-center justify-between text-sm text-text-muted">
      <span>
        共 <span class="tabular-nums text-text">{{ total }}</span> 条
      </span>
      <div class="flex items-center gap-2">
        <Button
          variant="outline"
          size="sm"
          data-testid="prev-page"
          :disabled="page <= 1"
          @click="changePage(page - 1)"
        >
          上一页
        </Button>
        <span class="tabular-nums">第 {{ page }} / {{ totalPages }} 页</span>
        <Button
          variant="outline"
          size="sm"
          data-testid="next-page"
          :disabled="page >= totalPages"
          @click="changePage(page + 1)"
        >
          下一页
        </Button>
      </div>
    </div>
  </template>

  <!-- ================================================================
         删除课程二次确认（danger 实底 + 不可恢复告警，设计 §2.6）
         ================================================================ -->
  <div
    v-if="deleting"
    data-testid="course-del-dialog"
    class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4"
    @keydown.esc="cancelDelete"
    @click.self="cancelDelete"
  >
    <div
      class="w-full max-w-[440px] rounded-xl border border-border bg-surface p-6 shadow-md"
      role="alertdialog"
      aria-modal="true"
      @click.stop
    >
      <div class="flex items-start gap-3">
        <div class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-red-50">
          <PhWarningCircle class="h-5 w-5 text-danger" />
        </div>
        <div>
          <h2 class="text-base font-semibold text-text">删除课程</h2>
          <p class="mt-2 text-sm leading-relaxed text-text-muted">
            删除后课程及其内容、排期与报名关系一并移除，
            <span class="font-medium text-danger">此操作不可恢复</span>。 确认删除「{{
              deleting.title
            }}」？
          </p>
        </div>
      </div>
      <div class="mt-5 flex justify-end gap-2">
        <Button
          variant="outline"
          data-testid="cancel-course-del"
          :disabled="deletingLoading"
          @click="cancelDelete"
        >
          取消
        </Button>
        <Button
          variant="danger"
          data-testid="confirm-course-del"
          :disabled="deletingLoading"
          @click="confirmDelete"
        >
          <PhSpinnerGap v-if="deletingLoading" class="h-4 w-4 animate-spin" />
          {{ deletingLoading ? '删除中' : '确认删除' }}
        </Button>
      </div>
    </div>
  </div>
</template>
