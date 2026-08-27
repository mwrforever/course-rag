<script setup lang="ts">
/**
 * 课程列表页（2026-08-27 紫系重制：PageHead + DataTable + 行操作下拉菜单）
 *
 * 能力清单：
 * 1. 页头（PageHead）：主标题 + 副题 + 右侧「新建课程」入口（/courses/new）
 * 2. 分页表格（DataTable）：封面缩略 48px（无封面占位）/ 名称 / 讲师 / 价格 / 课时 /
 *    学生数 / 状态 Badge（ACTIVE emerald / ARCHIVED 中性）/ 行操作下拉菜单（编辑·删除）
 * 3. 编辑跳转 /courses/{id}（与新建复用同一概览表单组件）
 * 4. 删除：危险操作二次确认（ConfirmDialog + submitting 拦截 Esc/遮罩）→
 *    DELETE → toast → 刷新（末页清空回退防空页）
 * 5. 分页：左下「共 N 条」+ 右下 上一页/下一页/页码
 * 6. 四态：loading 骨架 / empty（EmptyState 含新建入口）/ error 横幅重试 / 正常
 *
 * 契约要点：id/total/learningCount 为 Long 字符串铁律；价格/课时/人数数字域
 * tabular-nums；教师限己建课程由后端约束，前端不额外过滤。
 *
 * 线程安全注意：全部状态为组件私有 ref，无跨实例共享可变状态。
 */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { PhDotsThree, PhImageSquare, PhPencilSimple, PhPlus, PhTrash } from '@phosphor-icons/vue'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { DataTable } from '@/components/ui/data-table'
import { DropdownMenu, DropdownMenuItem } from '@/components/ui/dropdown-menu'
import { EmptyState } from '@/components/ui/empty-state'
import { PageHead } from '@/components/ui/page-head'
import { ApiError, courseApi } from '@/lib/api'
import { showToast } from '@/lib/toast'

import type { CourseDTO } from '@/lib/types'

/** 每页条数（分页器契约） */
const PAGE_SIZE = 10

/** 页码：查询键组成之一，变化自动触发新查询 */
const page = ref(1)

/** 查询键：页码变化即重拉当前页（vue-query 数据源，C.1.4） */
const queryKey = computed(() => ['admin-courses', page.value])

const {
  data,
  isLoading,
  isError,
  error: queryError,
  refetch,
} = useQuery({
  queryKey,
  queryFn: () => courseApi.list({ page: page.value, size: PAGE_SIZE }),
})

/** 列表行数据：total 为 Long 字符串铁律（契约 §D.4 同步口径） */
const courses = computed(() => data.value?.records ?? [])
const total = computed(() => data.value?.total ?? '0')

/** 总页数：total 为 Long 字符串，转 number 后按 PAGE_SIZE 上取整（至少 1 页） */
const totalPages = computed(() => Math.max(1, Math.ceil(Number(total.value) / PAGE_SIZE)))

/** 列表加载失败横幅文案（queryError 非空时透出；503 统一降级） */
const listError = computed(() =>
  isError.value ? messageOf(queryError.value, '课程列表加载失败，请稍后重试') : '',
)

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

/** 翻页：越界保护（首页/末页禁用态由 disabled 兜底，方法内再防一次），页码变化自动重拉 */
function changePage(next: number) {
  if (next < 1 || next > totalPages.value) return
  page.value = next
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

/** 状态 Badge：ACTIVE → emerald / ARCHIVED → 中性（状态可视化体系） */
function statusVariant(status: string) {
  return status === 'ACTIVE' ? ('success' as const) : ('default' as const)
}

// ====================================================================
// 删除二次确认（ConfirmDialog：受控开合 + submitting 拦截关闭）
// ====================================================================

/** 待删除课程：非 null 时展示确认 Dialog */
const deleting = ref<CourseDTO | null>(null)

const queryClient = useQueryClient()

/**
 * 删除课程提交（进行中由 loading 驱动按钮禁用；完成后按查询键失效重拉）
 *
 * 注意：isPending/mutate 顶层解构——useMutation 返回普通对象，模板中嵌套访问
 * 不会自动解包 ref（Ref 对象 truthy 会误触 disabled），解构后才是响应式顶层 ref。
 */
const { isPending: deletingSubmitting, mutate: submitDelete } = useMutation({
  mutationFn: (id: string) => courseApi.remove(id),
  onSuccess: () => {
    showToast('课程已删除', 'success')
    deleting.value = null
    // 删除末页最后一条会留下空页：回退一页（页码变化自动重拉）；否则失效当前列表键
    if (courses.value.length === 1 && page.value > 1) {
      page.value -= 1
    } else {
      queryClient.invalidateQueries({ queryKey: ['admin-courses'] })
    }
  },
  onError: (err) => {
    showToast(messageOf(err, '删除失败，请稍后重试'), 'danger')
  },
})

function requestDelete(c: CourseDTO) {
  deleting.value = c
}

/**
 * 行菜单「编辑」：先收起菜单再跳转编辑页
 *
 * @param c 目标课程行（Long 字符串 id 铁律）
 * @param close DropdownMenu 作用域插槽下发的收起函数
 */
function onMenuEdit(c: CourseDTO, close: () => void) {
  close()
  goEdit(c)
}

/**
 * 行菜单「删除」：先收起菜单再打开删除确认 Dialog
 *
 * @param c 目标课程行
 * @param close DropdownMenu 作用域插槽下发的收起函数
 */
function onMenuDelete(c: CourseDTO, close: () => void) {
  close()
  requestDelete(c)
}

/** 关闭确认 Dialog：提交期间拦截取消/Esc/遮罩（防误关丢状态；ConfirmDialog 关闭请求经此收口） */
function cancelDelete() {
  if (deletingSubmitting.value) return
  deleting.value = null
}

/**
 * 确认弹窗开合回抛（v-model 语义）
 *
 * @param open ConfirmDialog 回抛的开合值：仅 false 有意义（true 不会由组件发出）
 */
function onDelDialogOpen(open: boolean) {
  if (!open) cancelDelete()
}

/** 确认删除：提交中禁用按钮，完成/失败由 mutation 回调处理 */
function confirmDelete() {
  if (!deleting.value) return
  submitDelete(deleting.value.id)
}
</script>

<template>
  <div class="space-y-5">
    <!-- 页头：主标题 + 副题 + 右侧新建入口（列表态/空态共用同一跳转方法） -->
    <PageHead title="课程管理" subtitle="课程列表：编辑页涵盖内容 4 Tab、排期、教师分配与学生名单">
      <template #actions>
        <Button data-testid="create-course" @click="goCreate">
          <PhPlus class="h-4 w-4" />
          新建课程
        </Button>
      </template>
    </PageHead>

    <!-- 错误态：页内横幅 + 重试 -->
    <div
      v-if="listError"
      role="alert"
      class="flex items-center justify-between gap-4 rounded-xl border border-danger/30 bg-red-50 px-4 py-3"
    >
      <span class="text-sm text-danger">{{ listError }}</span>
      <Button variant="outline" size="sm" data-testid="retry-courses" @click="refetch">重试</Button>
    </div>

    <!-- 加载态：表格骨架屏（与最终表格同形） -->
    <div
      v-else-if="isLoading"
      data-testid="course-skeleton"
      class="overflow-hidden rounded-2xl border border-border bg-surface shadow-xs"
      aria-label="课程列表加载中"
    >
      <div class="flex items-center gap-6 border-b border-border bg-surface-2 px-6 py-3.5">
        <div
          v-for="i in 7"
          :key="`head-${i}`"
          class="h-3 w-20 animate-pulse rounded bg-slate-200"
        />
      </div>
      <div v-for="i in 5" :key="`row-${i}`" class="h-14 animate-pulse bg-slate-50" />
    </div>

    <!-- 空态：EmptyState + 新建入口（禁裸「暂无数据」） -->
    <div
      v-else-if="courses.length === 0"
      class="rounded-2xl border border-dashed border-border bg-surface shadow-xs"
    >
      <EmptyState title="还没有课程" description="新建第一个课程后即可配置内容、排期与学员">
        <template #icon>
          <PhImageSquare class="h-6 w-6" aria-hidden="true" />
        </template>
        <template #action>
          <Button data-testid="create-course-empty" @click="goCreate">新建课程</Button>
        </template>
      </EmptyState>
    </div>

    <!-- 正常态：分页表格（封面/名称/讲师/价格/课时/学生数/状态/行操作菜单） -->
    <template v-else>
      <!-- 表格卡：不可 overflow-hidden（行操作下拉菜单需向下溢出展开，避免被裁切） -->
      <div v-reveal class="rounded-2xl border border-border bg-surface pb-2 shadow-xs">
        <DataTable data-testid="course-table" label="课程列表">
          <template #header>
            <tr>
              <th class="w-[72px]">封面</th>
              <th>名称</th>
              <th class="w-28">讲师</th>
              <th class="w-24 text-right">价格</th>
              <th class="w-24">课时</th>
              <th class="w-24 text-right">学生数</th>
              <th class="w-24">状态</th>
              <th class="w-[64px] text-right">操作</th>
            </tr>
          </template>
          <tr v-for="c in courses" :key="c.id" :data-testid="`row-${c.id}`">
            <!-- 封面缩略 48px：URL 直出；无封面渲染占位（无上传接口 G11） -->
            <td>
              <img
                v-if="c.coverImage"
                :data-testid="`cover-${c.id}`"
                :src="c.coverImage"
                :alt="`${c.title} 封面`"
                class="h-12 w-12 rounded-[10px] border border-border bg-surface-2 object-cover"
              />
              <div
                v-else
                :data-testid="`cover-fallback-${c.id}`"
                class="flex h-12 w-12 items-center justify-center rounded-[10px] border border-border bg-surface-2"
              >
                <PhImageSquare class="h-5 w-5 text-text-subtle" />
              </div>
            </td>
            <td :data-testid="`course-title-${c.id}`" class="max-w-[240px]">
              <p class="truncate font-semibold text-text">{{ c.title }}</p>
            </td>
            <td :data-testid="`course-instructor-${c.id}`">{{ c.instructorName || '未指定' }}</td>
            <td class="text-right">
              <span
                :data-testid="`course-price-${c.id}`"
                class="tabular-nums font-semibold text-text"
              >
                ¥{{ c.price }}
              </span>
            </td>
            <td :data-testid="`course-duration-${c.id}`" class="tabular-nums">
              {{ c.duration || '未设置' }}
            </td>
            <td class="text-right">
              <span
                :data-testid="`course-learners-${c.id}`"
                class="tabular-nums font-semibold text-text"
              >
                {{ c.learningCount }}
              </span>
            </td>
            <td>
              <Badge :data-testid="`course-status-${c.id}`" :variant="statusVariant(c.status)">
                {{ c.status }}
              </Badge>
            </td>
            <td class="text-right">
              <!-- 行操作下拉菜单：编辑 / 删除（danger），收纳进 ⋮ 圆钮 -->
              <DropdownMenu>
                <template #trigger="{ toggle }">
                  <button
                    type="button"
                    class="row-menu-btn"
                    :data-testid="`row-menu-${c.id}`"
                    :aria-label="`课程操作：${c.title}`"
                    @click="toggle"
                  >
                    <PhDotsThree class="h-4 w-4" weight="bold" />
                  </button>
                </template>
                <template #default="{ close }">
                  <DropdownMenuItem
                    :data-testid="`op-edit-${c.id}`"
                    label="编辑"
                    @click="onMenuEdit(c, close)"
                  >
                    <template #icon>
                      <PhPencilSimple class="h-4 w-4" />
                    </template>
                  </DropdownMenuItem>
                  <DropdownMenuItem
                    :data-testid="`op-delete-${c.id}`"
                    label="删除"
                    tone="danger"
                    @click="onMenuDelete(c, close)"
                  >
                    <template #icon>
                      <PhTrash class="h-4 w-4" />
                    </template>
                  </DropdownMenuItem>
                </template>
              </DropdownMenu>
            </td>
          </tr>
        </DataTable>
      </div>

      <!-- 分页器：左「共 N 条」右 上/下页 + 页码 -->
      <div v-reveal="80" class="flex items-center justify-between text-sm text-text-muted">
        <span>
          共 <span class="tabular-nums font-semibold text-text">{{ total }}</span> 条
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
          删除课程二次确认（ConfirmDialog：danger 实底 + 不可恢复告警；
          提交期间 Esc/遮罩/取消关闭经 onDelDialogOpen → cancelDelete 拦截）
          ================================================================ -->
    <ConfirmDialog
      :open="deleting !== null"
      data-testid="confirm-course-del"
      title="删除课程"
      :description="`删除后课程及其内容、排期与报名关系一并移除，此操作不可恢复。确认删除「${deleting?.title ?? ''}」？`"
      confirm-text="确认删除"
      :loading="deletingSubmitting"
      @update:open="onDelDialogOpen"
      @confirm="confirmDelete"
    />
  </div>
</template>

<style scoped>
/* 行操作 ⋮ 圆钮：静置淡紫底，hover 紫底反白 + 弹簧放大轻旋（设计稿 eye-btn 交互曲线） */
.row-menu-btn {
  display: inline-grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: var(--radius-full);
  color: var(--color-text-muted);
  background: var(--color-brand-soft);
  transition:
    background-color 0.25s ease,
    color 0.25s ease,
    transform 0.35s var(--spring);
}
.row-menu-btn:hover {
  background: var(--color-brand);
  color: var(--color-surface);
  transform: scale(1.15) rotate(6deg);
}
.row-menu-btn:active {
  transform: scale(0.9);
}
</style>
