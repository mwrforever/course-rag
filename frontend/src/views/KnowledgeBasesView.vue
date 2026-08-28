<script setup lang="ts">
/**
 * 知识库管理页（设计 §2.4 知识库 CRUD 行 + §2.6 表格/表单/弹窗规范）
 *
 * 能力：列表分页表格（名称/描述/状态 Badge/创建时间）+ 新建/编辑 Dialog
 * （name 必填 zod 校验 + description）+ 删除二次确认（级联告警，不可恢复）。
 *
 * 契约要点：
 * - 列表仅返回 ACTIVE 状态（后端硬编码 eq ACTIVE），状态列按 ACTIVE→emerald 映射
 * - id/total 为 Long 字符串铁律；分页 page/size 为 number（PAGE_SIZE=10）
 * - 教师仅可见/可管自己创建（createdBy 由后端比对，前端不额外过滤）
 * - 四态：loading 表格骨架 / empty 空态（含行动入口）/ error 横幅重试 / 正常
 *
 * 可达性：路由 /knowledge-bases 已注册（Task 16），设计 §2.3 侧导航为
 * 「知识库：文档+分片」不新增导航项，从文档管理页「管理知识库」链接进入
 * （文档页随文档管理任务落地时补充入口）。
 *
 * N6a 视觉重制（2026-08-27 紫系换肤）：页头迁 PageHead、列表迁 DataTable
 * （lav 表头/悬停行/行级联入场）、行操作迁 DropdownMenu、删除确认迁
 * ConfirmDialog（confirm-delete testid 与级联告警文案契约保留）、空态迁
 * EmptyState、页头与表格卡 v-reveal 入场；查询/校验/提交逻辑零改动。
 */
import { computed, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import {
  PhDotsThreeVertical,
  PhNotePencil,
  PhPlus,
  PhSpinnerGap,
  PhTrash,
  PhWarningCircle,
} from '@phosphor-icons/vue'
import { z } from 'zod'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { DataTable } from '@/components/ui/data-table'
import { DropdownMenu, DropdownMenuItem } from '@/components/ui/dropdown-menu'
import { EmptyState } from '@/components/ui/empty-state'
import { PageHead } from '@/components/ui/page-head'
import { ApiError, knowledgeBaseApi } from '@/lib/api'
import { showToast } from '@/lib/toast'
import { formatDateTime } from '@/lib/utils'

import type { KnowledgeBaseVO } from '@/lib/types'

/** 每页条数（设计 §2.6 分页器：总数 + 上/下页） */
const PAGE_SIZE = 10

/** 页码：查询键组成之一，变化自动触发新查询 */
const page = ref(1)

/** 查询键：页码变化即重拉当前页（vue-query 数据源，C.1.4） */
const queryKey = computed(() => ['admin-knowledge-bases', page.value])

const {
  data,
  isLoading,
  isError,
  error: queryError,
  refetch,
} = useQuery({
  queryKey,
  queryFn: () => knowledgeBaseApi.list({ page: page.value, size: PAGE_SIZE }),
})

/** 列表行数据：total 为 Long 字符串铁律（契约 §D.4 同步口径） */
const kbs = computed(() => data.value?.records ?? [])
const total = computed(() => data.value?.total ?? '0')

/** 总页数：total 为 Long 字符串，转 number 后按 PAGE_SIZE 上取整（至少 1 页） */
const totalPages = computed(() => Math.max(1, Math.ceil(Number(total.value) / PAGE_SIZE)))

/** 列表加载失败横幅文案（queryError 非空时透出；503 统一降级） */
const listError = computed(() =>
  isError.value ? messageOf(queryError.value, '知识库加载失败，请稍后重试') : '',
)

/**
 * 接口错误分级文案（与登录页 messageOf 同构）
 *
 * @param err 捕获异常：ApiError 透出 message（503 统一降级文案）；未知异常页面兜底
 * @param fallback 非 ApiError 时的操作级兜底文案（由各操作场景传入）
 * @returns 展示文案
 */
function messageOf(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    return err.code === 503 ? '服务暂时不可用，请稍后重试' : err.message
  }
  return fallback
}

const queryClient = useQueryClient()

/** 写操作成功后的列表刷新：不减少行数的操作直接按查询键失效重拉（删除类见删除 mutation 内联回退） */
function refreshKbs() {
  queryClient.invalidateQueries({ queryKey: ['admin-knowledge-bases'] })
}

/** 翻页：越界保护（首页/末页禁用态由 disabled 兜底，方法内再防一次），页码变化自动重拉 */
function changePage(next: number) {
  if (next < 1 || next > totalPages.value) return
  page.value = next
}

// ====================================================================
// 新建 / 编辑 Dialog（name 必填 zod 前置校验，提交态防重复）
// ====================================================================

/** 表单校验 schema：name 必填（设计 §2.4 知识库行 + P2-12 后端 @Valid 对齐） */
const kbSchema = z.object({
  name: z.string().min(1, '请输入知识库名称'),
})

const dialogOpen = ref(false)
/** 当前编辑行：null 表示新建（标题「新建知识库」），非 null 表示编辑回填 */
const editing = ref<KnowledgeBaseVO | null>(null)
const form = ref({ name: '', description: '' })
const fieldError = ref('')

/** 新建/编辑提交（按 editing 分支走 create/update；成功后失效列表键） */
const { isPending: submitting, mutate: submitForm } = useMutation({
  mutationFn: async (payload: { name: string; description: string }) => {
    if (editing.value) {
      await knowledgeBaseApi.update(editing.value.id, payload)
    } else {
      await knowledgeBaseApi.create(payload)
    }
  },
  onSuccess: () => {
    showToast(editing.value ? '知识库已更新' : '知识库创建成功', 'success')
    dialogOpen.value = false
    refreshKbs()
  },
  onError: (err) => {
    showToast(messageOf(err, '保存失败，请稍后重试'), 'danger')
  },
})

/** 打开新建 Dialog：清空表单与错误 */
function openCreate() {
  editing.value = null
  form.value = { name: '', description: '' }
  fieldError.value = ''
  dialogOpen.value = true
}

/** 打开编辑 Dialog：行数据回填（name/description） */
function openEdit(kb: KnowledgeBaseVO) {
  editing.value = kb
  form.value = { name: kb.name, description: kb.description ?? '' }
  fieldError.value = ''
  dialogOpen.value = true
}

function closeDialog() {
  dialogOpen.value = false
}

/**
 * 提交表单：zod 校验（失败就地报错不发请求）→ 走 mutation（create/update）
 *
 * 成功后关闭 Dialog；失败 danger toast 展示后端文案且 Dialog 停留可重试。
 */
function handleSubmit() {
  const parsed = kbSchema.safeParse(form.value)
  if (!parsed.success) {
    fieldError.value = parsed.error.issues[0]?.message ?? '请输入知识库名称'
    return
  }
  fieldError.value = ''
  submitForm({ name: form.value.name, description: form.value.description })
}

// ====================================================================
// 删除二次确认（级联告警：文档与分片一并删除，不可恢复）
// ====================================================================

/** 待删除行：非 null 时展示确认 Dialog（danger 实底按钮需二次确认，设计 §2.6） */
const deleting = ref<KnowledgeBaseVO | null>(null)

/** 删除知识库提交（成功后失效列表键；删除末页最后一条会留空页——回退一页防空页） */
const { isPending: deletingLoading, mutate: confirmDeleteMutation } = useMutation({
  mutationFn: (id: string) => knowledgeBaseApi.remove(id),
  onSuccess: () => {
    showToast('知识库已删除', 'success')
    deleting.value = null
    if (kbs.value.length === 1 && page.value > 1) {
      page.value -= 1
    } else {
      queryClient.invalidateQueries({ queryKey: ['admin-knowledge-bases'] })
    }
  },
  onError: (err) => {
    showToast(messageOf(err, '删除失败，请稍后重试'), 'danger')
  },
})

function requestDelete(kb: KnowledgeBaseVO) {
  deleting.value = kb
}

/**
 * 行菜单「编辑」适配器（N6a 重制新增）：收起 ⋮ 菜单后打开编辑 Dialog
 *
 * @param kb 目标知识库行（来源列表数据）
 * @param close DropdownMenu 作用域插槽下发的收起方法
 */
function editFromMenu(kb: KnowledgeBaseVO, close: () => void) {
  close()
  openEdit(kb)
}

/**
 * 行菜单「删除」适配器（N6a 重制新增）：收起 ⋮ 菜单后进入删除二次确认
 *
 * @param kb 目标知识库行（来源列表数据）
 * @param close DropdownMenu 作用域插槽下发的收起方法
 */
function deleteFromMenu(kb: KnowledgeBaseVO, close: () => void) {
  close()
  requestDelete(kb)
}

function cancelDelete() {
  deleting.value = null
}

/** 确认删除：提交中禁用按钮，完成/失败由 mutation 回调处理 */
function confirmDelete() {
  if (!deleting.value) return
  confirmDeleteMutation(deleting.value.id)
}

/** 状态 Badge：ACTIVE → emerald / ARCHIVED → 中性（设计 §2.5；列表恒 ACTIVE 由后端过滤） */
function statusVariant(status: string) {
  return status === 'ACTIVE' ? ('success' as const) : ('default' as const)
}
</script>

<template>
  <!-- 页头：PageHead 统一形态（h1 22px/800 + 副题 13px muted + 右侧动作区），v-reveal 滚动入场 -->
  <PageHead v-reveal title="知识库管理" subtitle="仅展示 ACTIVE 状态知识库">
    <template #actions>
      <!-- 新建入口常驻页头（列表态/空态共用同一方法） -->
      <Button data-testid="create-kb" @click="openCreate">
        <PhPlus class="h-4 w-4" aria-hidden="true" />
        新建知识库
      </Button>
    </template>
  </PageHead>

  <!-- 错误态：页内横幅 + 重试（设计 §1.7） -->
  <div
    v-if="listError"
    role="alert"
    class="mt-5 flex items-center justify-between gap-4 rounded-xl border border-danger/30 bg-red-50 px-4 py-3"
  >
    <span class="text-sm text-danger">{{ listError }}</span>
    <Button variant="outline" size="sm" data-testid="retry-kb" @click="refetch">重试</Button>
  </div>

  <!-- 加载态：表格骨架屏（表头 + 5 行灰条与表格同形，设计 §1.7） -->
  <div
    v-else-if="isLoading"
    data-testid="kb-skeleton"
    class="mt-5 overflow-hidden rounded-2xl border border-border bg-surface"
    aria-label="知识库列表加载中"
  >
    <div class="flex items-center gap-6 border-b border-border bg-surface-2 px-5 py-3.5">
      <div v-for="i in 4" :key="`head-${i}`" class="h-3 w-20 animate-pulse rounded bg-slate-200" />
    </div>
    <div
      v-for="i in 5"
      :key="`row-${i}`"
      class="h-[54px] animate-pulse border-b border-border bg-slate-50 last:border-b-0"
    />
  </div>

  <!-- 空态：EmptyState 统一形态（图标圆 + 标题 + 引导文案 + 行动入口，禁裸「暂无数据」） -->
  <div
    v-else-if="kbs.length === 0"
    v-reveal
    class="mt-5 rounded-2xl border border-dashed border-border bg-surface"
  >
    <EmptyState title="还没有知识库" description="创建第一个知识库后即可上传文档">
      <template #icon>
        <PhWarningCircle class="h-6 w-6" aria-hidden="true" />
      </template>
      <template #action>
        <Button data-testid="create-kb-empty" @click="openCreate">新建知识库</Button>
      </template>
    </EmptyState>
  </div>

  <!-- 正常态：分页表格（DataTable 视觉壳：lav 圆角表头/行悬停高亮/行级联入场） -->
  <template v-else>
    <!--
      表格卡：16px 圆角 + 紫调柔影（设计稿 .table-card 形态）；
      不设 overflow-hidden——行操作下拉菜单为 absolute 定位，裁切上下文会把末行菜单裁掉
      （表头圆角由 DataTable 内部 thead th 半径承担）
    -->
    <div v-reveal="100" class="mt-5 rounded-2xl border border-border bg-surface pb-2 shadow-xs">
      <DataTable data-testid="kb-table" label="知识库列表">
        <template #header>
          <tr>
            <th class="w-[26%]">名称</th>
            <th class="w-[34%]">描述</th>
            <th class="w-[12%]">状态</th>
            <th class="w-[16%]">创建时间</th>
            <th class="w-[12%]">操作</th>
          </tr>
        </template>
        <!-- 行数据经默认插槽进 tbody（DataTable 已渲染 thead/tbody 骨架） -->
        <tr v-for="kb in kbs" :key="kb.id" :data-testid="`row-${kb.id}`">
          <!-- 名称列：主文字色 + 截断（title 悬浮全量，设计稿 .course-name 同规格） -->
          <td class="max-w-[260px] truncate" :title="kb.name">
            <span class="font-semibold text-text">{{ kb.name }}</span>
          </td>
          <!-- 描述列：次级文字色（td 基础色）+ 截断 -->
          <td class="max-w-[340px] truncate" :title="kb.description">
            {{ kb.description || '-' }}
          </td>
          <td>
            <Badge :variant="statusVariant(kb.status)">{{ kb.status }}</Badge>
          </td>
          <td>
            <span class="tabular-nums">{{ formatDateTime(kb.createdAt) }}</span>
          </td>
          <td>
            <!-- 行操作下拉：eye-btn 圆钮造型换 ⋮ 触发器，编辑/删除两项（删除 danger 色系） -->
            <DropdownMenu>
              <template #trigger="{ toggle }">
                <button
                  type="button"
                  :data-testid="`kb-menu-${kb.id}`"
                  aria-label="知识库操作菜单"
                  class="grid h-9 w-9 place-items-center rounded-full bg-brand-light text-text transition-all duration-200 hover:bg-brand hover:text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand active:scale-95"
                  @click="toggle"
                >
                  <PhDotsThreeVertical class="h-[18px] w-[18px]" aria-hidden="true" />
                </button>
              </template>
              <template #default="{ close }">
                <DropdownMenuItem
                  label="编辑"
                  :data-testid="`edit-${kb.id}`"
                  @click="editFromMenu(kb, close)"
                >
                  <template #icon>
                    <PhNotePencil class="h-4 w-4" aria-hidden="true" />
                  </template>
                </DropdownMenuItem>
                <DropdownMenuItem
                  label="删除"
                  tone="danger"
                  :data-testid="`delete-${kb.id}`"
                  @click="deleteFromMenu(kb, close)"
                >
                  <template #icon>
                    <PhTrash class="h-4 w-4" aria-hidden="true" />
                  </template>
                </DropdownMenuItem>
              </template>
            </DropdownMenu>
          </td>
        </tr>
      </DataTable>
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

  <!-- 新建/编辑 Dialog 480px（设计 §2.6：label 上置 + 必填 * + 错误红字 input 下方；遮罩点击关闭） -->
  <div
    v-if="dialogOpen"
    data-testid="kb-dialog"
    class="fixed inset-0 z-50 flex animate-fade-in items-center justify-center bg-overlay p-4"
    @keydown.esc="closeDialog"
    @click.self="closeDialog"
  >
    <div
      class="w-full max-w-[480px] animate-menu-in rounded-2xl bg-surface p-6 shadow-lg"
      role="dialog"
      aria-modal="true"
      @click.stop
    >
      <h2 class="text-base font-semibold text-text">{{ editing ? '编辑知识库' : '新建知识库' }}</h2>
      <form data-testid="kb-form" class="mt-5 space-y-4" novalidate @submit.prevent="handleSubmit">
        <div>
          <label for="kb-name" class="mb-1.5 block text-sm font-medium text-text">
            名称 <span class="text-danger">*</span>
          </label>
          <input
            id="kb-name"
            v-model="form.name"
            type="text"
            aria-label="知识库名称"
            autofocus
            placeholder="请输入知识库名称"
            class="h-10 w-full rounded-xl border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
          />
          <p v-if="fieldError" class="mt-1 text-xs text-danger">{{ fieldError }}</p>
        </div>
        <div>
          <label for="kb-desc" class="mb-1.5 block text-sm font-medium text-text">描述</label>
          <textarea
            id="kb-desc"
            v-model="form.description"
            rows="3"
            aria-label="知识库描述"
            placeholder="可选，简要说明该知识库的用途"
            class="w-full resize-none rounded-xl border border-border bg-surface px-3 py-2 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
          />
        </div>
        <div class="flex justify-end gap-2 pt-2">
          <Button variant="outline" @click="closeDialog">取消</Button>
          <Button type="submit" :disabled="submitting">
            <PhSpinnerGap v-if="submitting" class="h-4 w-4 animate-spin" aria-hidden="true" />
            {{ submitting ? '提交中' : editing ? '保存' : '创建' }}
          </Button>
        </div>
      </form>
    </div>
  </div>

  <!-- 删除二次确认：ConfirmDialog 统一壳（danger 确认键 + 级联告警，设计 §2.4/§2.6）；
       外层 v-if 承载 delete-dialog testid 契约（开=true 关=false），confirm-delete 经
       $attrs 落到确认按钮；Esc/遮罩点击经 update:open 回抛走取消 -->
  <div v-if="deleting" data-testid="delete-dialog">
    <ConfirmDialog
      open
      title="删除知识库"
      :description="`删除后该知识库下的全部文档与分片将被级联删除，且不可恢复。确认删除「${deleting.name}」？`"
      :confirm-text="deletingLoading ? '删除中' : '确认删除'"
      tone="danger"
      :loading="deletingLoading"
      data-testid="confirm-delete"
      @confirm="confirmDelete"
      @cancel="cancelDelete"
      @update:open="cancelDelete"
    />
  </div>
</template>
