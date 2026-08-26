<script setup lang="ts">
/**
 * 课程排期（UI 重构 2026-08-25 从 CourseEditView 拆出）
 *
 * 职责：排期表格（起止/类型/地点/讲师/容量/已报）+ 新增 Dialog + 行内编辑 Dialog +
 * 删除二次确认（全部提交期 Esc/遮罩/取消拦截）。
 */
import { computed, reactive, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { useRoute } from 'vue-router'
import { PhPencilSimple, PhPlus, PhSpinnerGap, PhTrash, PhWarningCircle } from '@phosphor-icons/vue'

import { Button } from '@/components/ui/button'
import { ApiError, scheduleApi } from '@/lib/api'
import { showToast } from '@/lib/toast'
import type { CourseScheduleVO } from '@/lib/types'

const route = useRoute()
/** 课程 id（Long 字符串铁律） */
const courseId = computed(() => String(route.params.id ?? ''))

/** 排期列表（查询键含路由 id，换课程自动重拉；失败 toast 保持原行为） */
const { data: schedulesData } = useQuery({
  queryKey: computed(() => ['course-schedules', courseId.value]),
  queryFn: async () => {
    try {
      return (await scheduleApi.listByCourse(courseId.value)) ?? []
    } catch (err) {
      showToast(messageOf(err, '排期加载失败，请稍后重试'), 'danger')
      throw err
    }
  },
})

/** 排期表格行数据（加载完成前为空数组兜底） */
const schedules = computed(() => schedulesData.value ?? [])

const queryClient = useQueryClient()

/** 写操作成功后的排期刷新：按查询键失效重拉 */
function refreshSchedules() {
  queryClient.invalidateQueries({ queryKey: ['course-schedules'] })
}

/** 排期表单承载：capacity 字符串承载，提交时数值化（可为空） */
const scheduleForm = reactive({
  startDate: '',
  endDate: '',
  scheduleType: 'ONLINE',
  location: '',
  instructorName: '',
  capacity: '',
})
/** 排期 Dialog 打开态；scheduleEditing 非 null 表示编辑（标题「编辑排期」） */
const scheduleDialogOpen = ref(false)
const scheduleEditing = ref<CourseScheduleVO | null>(null)
const scheduleError = ref('')
/** 删除确认：非 null 展示二次确认 Dialog */
const scheduleDeleting = ref<CourseScheduleVO | null>(null)

function messageOf(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    return err.code === 503 ? '服务暂时不可用，请稍后重试' : err.message
  }
  return fallback
}

/** 打开新增排期 Dialog：清空表单与错误，编辑态置 null */
function openCreateSchedule() {
  scheduleEditing.value = null
  scheduleForm.startDate = ''
  scheduleForm.endDate = ''
  scheduleForm.scheduleType = 'ONLINE'
  scheduleForm.location = ''
  scheduleForm.instructorName = ''
  scheduleForm.capacity = ''
  scheduleError.value = ''
  scheduleDialogOpen.value = true
}

/** 打开行内编辑 Dialog：行数据回填（capacity 数值 → 字符串） */
function openEditSchedule(s: CourseScheduleVO) {
  scheduleEditing.value = s
  scheduleForm.startDate = s.startDate
  scheduleForm.endDate = s.endDate
  scheduleForm.scheduleType = s.scheduleType
  scheduleForm.location = s.location ?? ''
  scheduleForm.instructorName = s.instructorName ?? ''
  scheduleForm.capacity = s.capacity === 0 ? '' : String(s.capacity)
  scheduleError.value = ''
  scheduleDialogOpen.value = true
}

/** 关闭排期 Dialog：提交期间拦截（防误关丢提交态） */
function closeScheduleDialog() {
  if (scheduleSubmitting.value) return
  scheduleDialogOpen.value = false
}

/** 排期表单校验：起止日期必填（就地报错不发请求） */
function validateSchedule(): boolean {
  if (!scheduleForm.startDate) {
    scheduleError.value = '请输入开始日期'
    return false
  }
  if (!scheduleForm.endDate) {
    scheduleError.value = '请输入结束日期'
    return false
  }
  scheduleError.value = ''
  return true
}

/** 排期保存提交（新增 create / 编辑 update 全字段；成功后失效列表键） */
const { isPending: scheduleSubmitting, mutate: submitScheduleMutation } = useMutation({
  mutationFn: async (): Promise<void> => {
    const capacity = scheduleForm.capacity === '' ? undefined : Number(scheduleForm.capacity)
    if (scheduleEditing.value) {
      await scheduleApi.update(scheduleEditing.value.id, {
        startDate: scheduleForm.startDate,
        endDate: scheduleForm.endDate,
        scheduleType: scheduleForm.scheduleType,
        location: scheduleForm.location,
        instructorName: scheduleForm.instructorName,
        capacity,
      })
    } else {
      await scheduleApi.create(courseId.value, {
        startDate: scheduleForm.startDate,
        endDate: scheduleForm.endDate,
        scheduleType: scheduleForm.scheduleType,
        location: scheduleForm.location,
        instructorName: scheduleForm.instructorName,
        capacity,
      })
    }
  },
  onSuccess: () => {
    showToast('排期已保存', 'success')
    scheduleDialogOpen.value = false
    refreshSchedules()
  },
  onError: (err) => {
    showToast(messageOf(err, '排期保存失败，请稍后重试'), 'danger')
  },
})

/** 排期保存：表单校验（就地报错不发请求）→ 走 mutation */
function submitSchedule() {
  if (!validateSchedule()) return
  submitScheduleMutation()
}

/** 删除排期提交（成功后失效列表键） */
const { isPending: scheduleDeletingLoading, mutate: confirmDeleteScheduleMutation } = useMutation({
  mutationFn: (id: string) => scheduleApi.remove(id),
  onSuccess: () => {
    showToast('排期已删除', 'success')
    scheduleDeleting.value = null
    refreshSchedules()
  },
  onError: (err) => {
    showToast(messageOf(err, '排期删除失败，请稍后重试'), 'danger')
  },
})

/** 打开删除确认 Dialog */
function requestDeleteSchedule(s: CourseScheduleVO) {
  scheduleDeleting.value = s
}

/** 取消删除确认（提交期间拦截） */
function cancelDeleteSchedule() {
  if (scheduleDeletingLoading.value) return
  scheduleDeleting.value = null
}

/** 确认删除排期：提交中禁用按钮，完成/失败由 mutation 回调处理 */
function confirmDeleteSchedule() {
  if (!scheduleDeleting.value) return
  confirmDeleteScheduleMutation(scheduleDeleting.value.id)
}
</script>

<template>
  <section class="overflow-hidden rounded-xl border border-border bg-surface">
    <div class="flex items-center justify-between border-b border-border px-6 py-4">
      <h2 class="text-base font-semibold text-text">
        排期
        <span class="ml-2 text-sm font-normal text-text-muted">共 {{ schedules.length }} 个</span>
      </h2>
      <Button size="sm" data-testid="add-schedule" @click="openCreateSchedule">
        <PhPlus class="h-4 w-4" />
        新增排期
      </Button>
    </div>

    <!-- 排期空态 -->
    <div
      v-if="schedules.length === 0"
      class="flex flex-col items-center justify-center py-10 text-center"
    >
      <PhWarningCircle class="h-6 w-6 text-text-subtle" />
      <p class="mt-2 text-sm text-text-muted">还没有排期，点击新增排期添加课程安排</p>
    </div>

    <!-- 排期表格：起止/类型/地点/讲师/容量/已报 + 操作 -->
    <table v-else data-testid="schedule-table" class="w-full text-sm">
      <thead class="border-b border-border bg-surface-2 text-left text-xs text-text-muted">
        <tr>
          <th class="px-4 py-2.5 font-medium">起止日期</th>
          <th class="w-20 px-4 py-2.5 font-medium">类型</th>
          <th class="max-w-[140px] px-4 py-2.5 font-medium">地点</th>
          <th class="max-w-[120px] px-4 py-2.5 font-medium">讲师</th>
          <th class="w-16 px-4 py-2.5 text-right font-medium">容量</th>
          <th class="w-16 px-4 py-2.5 text-right font-medium">已报</th>
          <th class="w-32 px-4 py-2.5 text-right font-medium">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="s in schedules"
          :key="s.id"
          :data-testid="`schedule-row-${s.id}`"
          class="h-11 border-b border-border transition-colors duration-150 last:border-b-0 hover:bg-surface-2"
        >
          <td class="px-4">
            <span class="tabular-nums text-text">{{ s.startDate }}</span>
            <span class="mx-1 text-text-subtle">至</span>
            <span class="tabular-nums text-text-muted">{{ s.endDate }}</span>
          </td>
          <td class="px-4 text-text-muted">{{ s.scheduleType }}</td>
          <td class="max-w-[140px] truncate px-4 text-text-muted" :title="s.location">
            {{ s.location || '-' }}
          </td>
          <td class="max-w-[120px] truncate px-4 text-text-muted" :title="s.instructorName">
            {{ s.instructorName || '-' }}
          </td>
          <td class="px-4 text-right tabular-nums text-text">{{ s.capacity }}</td>
          <td class="px-4 text-right tabular-nums text-text-muted">{{ s.enrolled }}</td>
          <td class="px-4 text-right">
            <div class="flex items-center justify-end gap-1">
              <Button
                variant="ghost"
                size="sm"
                :data-testid="`op-schedule-edit-${s.id}`"
                @click="openEditSchedule(s)"
              >
                <PhPencilSimple class="h-3.5 w-3.5" />
                编辑
              </Button>
              <Button
                variant="ghost"
                size="sm"
                class="text-danger hover:bg-danger/5"
                :data-testid="`op-schedule-del-${s.id}`"
                @click="requestDeleteSchedule(s)"
              >
                <PhTrash class="h-3.5 w-3.5" />
                删除
              </Button>
            </div>
          </td>
        </tr>
      </tbody>
    </table>

    <!-- 排期新增/编辑 Dialog（480px；提交期 Esc/遮罩/取消全拦截） -->
    <div
      v-if="scheduleDialogOpen"
      data-testid="schedule-dialog"
      class="fixed inset-0 z-50 flex items-center justify-center bg-overlay p-4"
      @keydown.esc="closeScheduleDialog"
      @click.self="closeScheduleDialog"
    >
      <div
        class="animate-menu-in w-full max-w-[480px] rounded-xl border border-border bg-surface p-6 shadow-lg"
        role="dialog"
        aria-modal="true"
      >
        <h2 class="text-base font-semibold text-text">
          {{ scheduleEditing ? '编辑排期' : '新增排期' }}
        </h2>
        <div class="mt-5 space-y-4">
          <div class="grid grid-cols-2 gap-4">
            <div>
              <label for="schedule-start-date" class="mb-1.5 block text-sm font-medium text-text">
                开始日期 <span class="text-danger">*</span>
              </label>
              <input
                id="schedule-start-date"
                v-model="scheduleForm.startDate"
                type="date"
                data-testid="schedule-start"
                aria-label="开始日期"
                class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm tabular-nums text-text outline-none transition-colors duration-150 focus:border-brand focus:ring-2 focus:ring-brand/20"
              />
            </div>
            <div>
              <label for="schedule-end-date" class="mb-1.5 block text-sm font-medium text-text">
                结束日期 <span class="text-danger">*</span>
              </label>
              <input
                id="schedule-end-date"
                v-model="scheduleForm.endDate"
                type="date"
                data-testid="schedule-end"
                aria-label="结束日期"
                class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm tabular-nums text-text outline-none transition-colors duration-150 focus:border-brand focus:ring-2 focus:ring-brand/20"
              />
            </div>
          </div>
          <div>
            <label for="schedule-type" class="mb-1.5 block text-sm font-medium text-text"
              >类型</label
            >
            <select
              id="schedule-type"
              v-model="scheduleForm.scheduleType"
              data-testid="schedule-type"
              aria-label="排期类型"
              class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 focus:border-brand focus:ring-2 focus:ring-brand/20"
            >
              <option value="ONLINE">ONLINE（线上）</option>
              <option value="OFFLINE">OFFLINE（线下）</option>
              <option value="HYBRID">HYBRID（混合）</option>
            </select>
          </div>
          <div>
            <label for="schedule-location" class="mb-1.5 block text-sm font-medium text-text"
              >地点</label
            >
            <input
              id="schedule-location"
              v-model="scheduleForm.location"
              type="text"
              data-testid="schedule-location"
              aria-label="排期地点"
              placeholder="如 腾讯会议 / 上海教室"
              class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
            />
          </div>
          <div class="grid grid-cols-2 gap-4">
            <div>
              <label for="schedule-instructor" class="mb-1.5 block text-sm font-medium text-text">
                讲师
              </label>
              <input
                id="schedule-instructor"
                v-model="scheduleForm.instructorName"
                type="text"
                data-testid="schedule-instructor"
                aria-label="排期讲师"
                placeholder="主讲老师"
                class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
              />
            </div>
            <div>
              <label for="schedule-capacity" class="mb-1.5 block text-sm font-medium text-text">
                容量
              </label>
              <input
                id="schedule-capacity"
                v-model="scheduleForm.capacity"
                type="number"
                min="0"
                data-testid="schedule-capacity"
                aria-label="排期容量"
                placeholder="人数上限"
                class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm tabular-nums text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
              />
            </div>
          </div>
          <p v-if="scheduleError" data-testid="schedule-error" class="text-xs text-danger">
            {{ scheduleError }}
          </p>
        </div>
        <div class="mt-5 flex justify-end gap-2">
          <Button
            variant="outline"
            data-testid="cancel-schedule"
            :disabled="scheduleSubmitting"
            @click="closeScheduleDialog"
          >
            取消
          </Button>
          <Button
            data-testid="submit-schedule"
            :disabled="scheduleSubmitting"
            @click="submitSchedule"
          >
            <PhSpinnerGap v-if="scheduleSubmitting" class="h-4 w-4 animate-spin" />
            {{ scheduleSubmitting ? '保存中' : '保存' }}
          </Button>
        </div>
      </div>
    </div>

    <!-- 排期删除二次确认（danger 实底；提交期拦截关闭） -->
    <div
      v-if="scheduleDeleting"
      data-testid="schedule-del-dialog"
      class="fixed inset-0 z-50 flex items-center justify-center bg-overlay p-4"
      @keydown.esc="cancelDeleteSchedule"
      @click.self="cancelDeleteSchedule"
    >
      <div
        class="animate-menu-in w-full max-w-[440px] rounded-xl border border-border bg-surface p-6 shadow-lg"
        role="alertdialog"
        aria-modal="true"
      >
        <div class="flex items-start gap-3">
          <div class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-danger/10">
            <PhWarningCircle class="h-5 w-5 text-danger" />
          </div>
          <div>
            <h2 class="text-base font-semibold text-text">删除排期</h2>
            <p class="mt-2 text-sm leading-relaxed text-text-muted">
              将删除
              <span class="tabular-nums">{{ scheduleDeleting.startDate }}</span>
              至
              <span class="tabular-nums">{{ scheduleDeleting.endDate }}</span>
              的排期，此操作不可恢复。确认删除？
            </p>
          </div>
        </div>
        <div class="mt-5 flex justify-end gap-2">
          <Button
            variant="outline"
            data-testid="cancel-schedule-del"
            :disabled="scheduleDeletingLoading"
            @click="cancelDeleteSchedule"
          >
            取消
          </Button>
          <Button
            variant="danger"
            data-testid="confirm-schedule-del"
            :disabled="scheduleDeletingLoading"
            @click="confirmDeleteSchedule"
          >
            <PhSpinnerGap v-if="scheduleDeletingLoading" class="h-4 w-4 animate-spin" />
            {{ scheduleDeletingLoading ? '删除中' : '确认删除' }}
          </Button>
        </div>
      </div>
    </div>
  </section>
</template>
