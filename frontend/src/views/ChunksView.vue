<script setup lang="ts">
/**
 * 分片修正工作台（设计 §2.4.3，B 端核心页）
 *
 * 能力清单：
 * 1. 默认视图：GET admin/chunks/pending（correction_status=PENDING 固定）；
 *    筛选仅 kbId/docId 两参（后端无更多参数 G2-G4），分页
 * 2. 表格：☐ / 内容预览（2 行截断 + 全文 tooltip）/ 所属文档（docId 短格式 + kbId 原文）/
 *    collection_type Badge（TECHNICAL_QA 蓝 / COURSE_INFO 紫 / null 灰「未分类」）/
 *    courseId（「通用」灰 Badge 或 id 短格式，无课程名映射接口）/ 操作（上下文/编辑）
 * 3. 批量修正 Dialog（480px）：collectionType 下拉 + courseId 课程搜索选择器，
 *    「不改」语义 = 提交省略对应字段，「通用(DEFAULT)」courseId 显式 'DEFAULT'
 *    （后端 if (courseId != null) 判定，非 null 即写库并同步 Milvus）；
 *    POST batch-update 带 loading 态（文档级 Milvus 同步可能慢）
 * 4. 标记已修正：二次确认（danger + 不可撤销文案）→ POST batch-corrected → 行消失
 * 5. 编辑 Drawer（600px）：mono textarea 全文 + 元数据只读区（headingPath/charOffset
 *    起止/tokenCount）；保存 PUT {id} {content}，toast「内容已更新，正在重新向量化…」
 * 6. 上下文 Drawer（600px）：parent/prev/current/next 时间线，null 节点不渲染，
 *    加载失败可重试
 * 7. 四态：loading 骨架 / empty / error 横幅重试 / 正常
 *
 * 契约要点：id/total 为 Long 字符串铁律；page/size 为 number。
 * 单片 PATCH collection-type 入口弱化（G17：单片不同步 Milvus），统一引导批量通道。
 *
 * 线程安全注意：全部状态为组件私有 ref，无跨实例共享可变状态。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import {
  PhCheckCircle,
  PhListDashes,
  PhMagnifyingGlass,
  PhPencilLine,
  PhSpinnerGap,
  PhWarningCircle,
  PhX,
} from '@phosphor-icons/vue'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ApiError, chunkApi, courseApi, knowledgeBaseApi } from '@/lib/api'
import { showToast } from '@/lib/toast'

import type { CollectionType, CourseDTO, DocumentChunkVO, KnowledgeBaseVO } from '@/lib/types'

/** 每页条数（设计 §2.6 分页器） */
const PAGE_SIZE = 10

/** 集合类型选项（设计 §2.4.3：TECHNICAL_QA 蓝 / COURSE_INFO 紫；空值 = 不改） */
const COLLECTION_TYPE_OPTIONS: Array<{ value: '' | CollectionType; label: string }> = [
  { value: '', label: '不改（保持现有类型）' },
  { value: 'TECHNICAL_QA', label: '技术问答（TECHNICAL_QA）' },
  { value: 'COURSE_INFO', label: '课程信息（COURSE_INFO）' },
]

/**
 * 批量修正 Dialog 的课程选择三态：
 * - keep：不改（提交省略 courseId 字段，后端 null 不更新）
 * - default：通用（DEFAULT），courseId 显式传 'DEFAULT'
 *   （后端 if (courseId != null) 判定，非 null 即写库并同步 Milvus——真实数据形态即字符串 'DEFAULT'）
 * - course：绑定具体课程，提交 courseId = course.id
 */
type CourseChoice = { kind: 'keep' } | { kind: 'default' } | { kind: 'course'; course: CourseDTO }

/** 上下文 Drawer 节点顺序与中文标签（设计 §2.4.3：时间线四节点） */
const CONTEXT_NODES: Array<{ key: 'parent' | 'prev' | 'current' | 'next'; label: string }> = [
  { key: 'parent', label: '父分片' },
  { key: 'prev', label: '前一分片' },
  { key: 'current', label: '当前分片' },
  { key: 'next', label: '下一分片' },
]

/**
 * ID 短格式（设计 G10 同款：# 号 + 后 6 位）
 *
 * 无名称映射场景（所属文档 docId / 课程 id）的紧凑展示，全文放 title tooltip。
 *
 * @param id 后端 Long 序列化字符串
 * @returns 如「#123456」
 */
function shortId(id: string): string {
  return `#${String(id).slice(-6)}`
}

/**
 * 接口错误分级文案（与文档列表/详情页 messageOf 同构）
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

// ====================================================================
// 待修正列表（vue-query 数据源 + kbId/docId 两参筛选 + 分页）
// ====================================================================

/** 筛选条件：kbId 下拉即选即查；docId 经「应用」按钮提交（与 q 输入同模式） */
const filters = reactive({ kbId: '', docId: '' })
/** docId 输入框草稿：提交前不写入 filters */
const docInput = ref('')
const page = ref(1)

/** 查询参数构造：空筛选值不携带（axios 端 undefined 参数同样被忽略） */
function buildListParams() {
  return {
    ...(filters.kbId ? { kbId: filters.kbId } : {}),
    ...(filters.docId ? { docId: filters.docId } : {}),
    page: page.value,
    size: PAGE_SIZE,
  }
}

/** 查询键：kbId/docId/页码任一变化即触发新查询 */
const queryKey = computed(() => [
  'admin-chunks-pending',
  { kbId: filters.kbId, docId: filters.docId },
  page.value,
])

const {
  data,
  isLoading,
  isError,
  error: queryError,
  refetch,
} = useQuery({
  queryKey,
  queryFn: async () => {
    const res = await chunkApi.pending(buildListParams())
    return res
  },
})

const chunks = computed(() => data.value?.records ?? [])
const total = computed(() => data.value?.total ?? '0')
const totalPages = computed(() => Math.max(1, Math.ceil(Number(total.value) / PAGE_SIZE)))

const listError = computed(() =>
  isError.value ? messageOf(queryError.value, '分片列表加载失败，请稍后重试') : '',
)

/** 知识库选项（筛选下拉），加载失败不阻塞列表 */
const kbs = ref<KnowledgeBaseVO[]>([])

onMounted(async () => {
  try {
    const res = await knowledgeBaseApi.list({ page: 1, size: 100 })
    kbs.value = res.records ?? []
  } catch {
    // 知识库选项加载失败：筛选仅剩「全部知识库」，重进页面可恢复
  }
})

/** kbId 筛选变更：重置页码第 1 页（查询键变化自动触发拉取） */
function onFilterKbChange(e: Event) {
  filters.kbId = (e.target as HTMLSelectElement).value
  page.value = 1
}

/** docId 筛选提交（应用按钮 / 回车）：去空白后写入 filters 并重置页码 */
function applyDocFilter() {
  filters.docId = docInput.value.trim()
  page.value = 1
}

/** 翻页：越界保护（禁用态按钮兜底） */
function changePage(next: number) {
  if (next < 1 || next > totalPages.value) return
  page.value = next
}

// ====================================================================
// 勾选状态管理（跨页保留，批量操作以勾选集合为准）
// ====================================================================

const selected = ref<Set<string>>(new Set())

/** 单行勾选切换（不可变 Set 拷贝写回，触发响应式） */
function toggleRow(id: string) {
  const next = new Set(selected.value)
  if (next.has(id)) {
    next.delete(id)
  } else {
    next.add(id)
  }
  selected.value = next
}

/** 全选/取消全选当前页 */
function toggleAll() {
  const allChecked = chunks.value.length > 0 && chunks.value.every((c) => selected.value.has(c.id))
  selected.value = allChecked ? new Set() : new Set(chunks.value.map((c) => c.id))
}

/** 当前页全选判定（驱动表头全选框） */
const allSelected = computed(
  () => chunks.value.length > 0 && chunks.value.every((c) => selected.value.has(c.id)),
)

// ====================================================================
// 批量修正 Dialog（collectionType 下拉 + courseId 课程搜索选择器）
// ====================================================================

const batchDialogOpen = ref(false)
const batchCollectionType = ref<'' | CollectionType>('')
const batchSubmitting = ref(false)

/** 课程选择三态（默认「不改」）；远程搜索 state 与上传 Dialog 同构 */
const batchCourseChoice = ref<CourseChoice>({ kind: 'keep' })
const batchCourseQuery = ref('')
const batchCourseResults = ref<CourseDTO[]>([])
const batchCourseSearching = ref(false)

/** 表单校验：collectionType 与 courseId 均为「不改」时无实际改动，禁用提交 */
const batchSubmitDisabled = computed(
  () => !batchCollectionType.value && batchCourseChoice.value.kind === 'keep',
)

function openBatchDialog() {
  batchDialogOpen.value = true
  batchCollectionType.value = ''
  batchCourseChoice.value = { kind: 'keep' }
  batchCourseQuery.value = ''
  batchCourseResults.value = []
}

function closeBatchDialog() {
  if (batchSubmitting.value) return
  batchDialogOpen.value = false
}

/** 课程远程搜索：输入即查（size 10），搜索失败静默清空结果 */
async function searchBatchCourses() {
  const keyword = batchCourseQuery.value.trim()
  if (!keyword) {
    batchCourseResults.value = []
    return
  }
  batchCourseSearching.value = true
  try {
    const res = await courseApi.list({ keyword, size: 10 })
    batchCourseResults.value = res.records ?? []
  } catch {
    batchCourseResults.value = []
  } finally {
    batchCourseSearching.value = false
  }
}

/** 选中课程（三态之一）：收起结果列表，输入框切换为 chip 展示 */
function pickBatchCourse(choice: CourseChoice) {
  batchCourseChoice.value = choice
  batchCourseResults.value = []
}

/**
 * 批量修正提交体组装（设计 §2.4.3 工作流第 3 步）
 *
 * 「不改」= 省略对应字段：collectionType 留空省之；course 三态仅 keep 省略 courseId，
 * default 显式传 'DEFAULT'（后端 if (courseId != null) 判定，非 null 即实际写库并同步
 * Milvus；真实数据形态即字符串 'DEFAULT'，与表格「通用」Badge 口径一致）。
 *
 * @returns 提交体 {ids, collectionType?, courseId?}
 */
function buildBatchBody() {
  const body: { ids: string[]; collectionType?: CollectionType; courseId?: string } = {
    ids: [...selected.value],
  }
  if (batchCollectionType.value) {
    body.collectionType = batchCollectionType.value
  }
  if (batchCourseChoice.value.kind === 'course') {
    body.courseId = batchCourseChoice.value.course.id
  } else if (batchCourseChoice.value.kind === 'default') {
    body.courseId = 'DEFAULT'
  }
  return body
}

/**
 * 提交批量修正：POST batch-update（loading 态，文档级 Milvus 同步可能慢）
 *
 * 成功后 toast、关闭 Dialog、清空勾选并刷新列表（工作流继续进入「标记已修正」）；
 * 失败 toast danger 且 Dialog 保留可重试。
 */
async function submitBatchUpdate() {
  if (batchSubmitDisabled.value) return
  batchSubmitting.value = true
  try {
    await chunkApi.batchUpdate(buildBatchBody())
    showToast('批量修正完成', 'success')
    batchDialogOpen.value = false
    selected.value = new Set()
    await refetch()
  } catch (err) {
    showToast(messageOf(err, '批量修正失败，请稍后重试'), 'danger')
  } finally {
    batchSubmitting.value = false
  }
}

// ====================================================================
// 标记已修正（二次确认：danger + 不可撤销文案）
// ====================================================================

const correctedConfirmOpen = ref(false)
const correctedSubmitting = ref(false)

/** 关闭确认 Dialog：提交期间拦截取消/Esc/遮罩（与批量 Dialog submitting 一致，防误关丢状态） */
function closeCorrectedConfirm() {
  if (correctedSubmitting.value) return
  correctedConfirmOpen.value = false
}

/**
 * 确认标记已修正：POST batch-corrected {ids}
 *
 * 后端将 correction_status 置为 CORRECTED（不可撤销，PENDING → CORRECTED 单向）：
 * 成功后清空勾选并刷新列表，已标记行移出待修正视图。
 */
async function confirmBatchCorrected() {
  const ids = [...selected.value]
  if (ids.length === 0) return
  correctedSubmitting.value = true
  try {
    await chunkApi.batchCorrected({ ids })
    showToast(`已标记 ${ids.length} 个分片为已修正`, 'success')
    correctedConfirmOpen.value = false
    selected.value = new Set()
    await refetch()
  } catch (err) {
    showToast(messageOf(err, '标记失败，请稍后重试'), 'danger')
  } finally {
    correctedSubmitting.value = false
  }
}

// ====================================================================
// 编辑 Drawer（600px：mono 全文 + 元数据只读区 + 保存重新向量化）
// ====================================================================

const editTarget = ref<DocumentChunkVO | null>(null)
const editContent = ref('')
const editError = ref('')
const editSaving = ref(false)

/** 打开编辑：行数据直供（列表已含全部元数据字段，无需二次请求） */
function openEdit(c: DocumentChunkVO) {
  editTarget.value = c
  editContent.value = c.content
  editError.value = ''
}

function closeEdit() {
  if (editSaving.value) return
  editTarget.value = null
}

/**
 * 保存编辑：PUT admin/chunks/{id} {content}
 *
 * 改 content 触发重新向量化：成功后 toast「内容已更新，正在重新向量化…」，
 * 关闭 Drawer 并刷新列表（更新后的内容回流列表预览）。
 */
async function submitEdit() {
  if (!editTarget.value) return
  const content = editContent.value.trim()
  if (!content) {
    editError.value = '请输入分片内容'
    return
  }
  editSaving.value = true
  try {
    await chunkApi.updateContent(editTarget.value.id, { content })
    showToast('内容已更新，正在重新向量化…', 'success')
    editTarget.value = null
    await refetch()
  } catch (err) {
    showToast(messageOf(err, '保存失败，请稍后重试'), 'danger')
  } finally {
    editSaving.value = false
  }
}

// ====================================================================
// 上下文 Drawer（600px：parent/prev/current/next 时间线，null 不渲染）
// ====================================================================

const contextOpen = ref(false)
const contextLoading = ref(false)
const contextError = ref('')
/** 当前上下文主分片 id（错误重试复用） */
const contextChunkId = ref('')
/** 时间线节点（按 CONTEXT_NODES 顺序过滤 null 后填充） */
const contextNodes = ref<Array<{ key: string; label: string; chunk: DocumentChunkVO }>>([])
/** 加载请求序号：快速开关竞态守卫——响应仅在序号仍为最新时写入，过期响应丢弃 */
const contextLoadSeq = ref(0)

/** 上下文接口返回四键 Map（value 为 DocumentChunkVO 或 null） */
type ContextMap = Record<string, DocumentChunkVO | null>

async function openContext(c: DocumentChunkVO) {
  contextOpen.value = true
  contextChunkId.value = c.id
  await loadContext(c.id)
}

/**
 * 拉取上下文：四键过滤 null → 时间线节点（key 顺序固定 parent→prev→current→next）
 *
 * 竞态守卫：每次调用自增请求序号，响应/异常/收尾仅当序号仍为最新时才写入状态；
 * closeContext 自增序号使在途请求作废——开 A→关→开 B 时 A 的迟到响应不得回填覆盖 B。
 */
async function loadContext(id: string) {
  const seq = ++contextLoadSeq.value
  contextLoading.value = true
  contextError.value = ''
  try {
    const map = (await chunkApi.context(id)) as ContextMap
    if (seq !== contextLoadSeq.value) return
    contextNodes.value = CONTEXT_NODES.flatMap((n) =>
      map[n.key] ? [{ ...n, chunk: map[n.key] as DocumentChunkVO }] : [],
    )
  } catch (err) {
    if (seq !== contextLoadSeq.value) return
    contextError.value = messageOf(err, '上下文加载失败，请稍后重试')
  } finally {
    if (seq === contextLoadSeq.value) {
      contextLoading.value = false
    }
  }
}

function closeContext() {
  // 使在途请求序号过期：迟到响应不得回填状态（含 loading/error 清理）
  contextLoadSeq.value++
  contextOpen.value = false
  contextNodes.value = []
  contextLoading.value = false
  contextError.value = ''
}
</script>

<template>
  <main class="mx-auto max-w-[1400px] px-8 py-6">
    <!-- 页头操作行：流程说明 + 批量修正 / 标记已修正（勾选后出现） -->
    <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
      <p class="text-sm text-text-muted">
        分片修正工作台：修正内容分类后标记已修正，批量修正按文档级同步向量化
      </p>
      <div class="flex items-center gap-2">
        <Button
          v-if="selected.size > 0"
          data-testid="batch-update"
          size="sm"
          @click="openBatchDialog"
        >
          <PhPencilLine class="h-4 w-4" />
          批量修正（{{ selected.size }}）
        </Button>
        <Button
          v-if="selected.size > 0"
          variant="danger"
          size="sm"
          data-testid="batch-corrected"
          @click="correctedConfirmOpen = true"
        >
          <PhCheckCircle class="h-4 w-4" />
          标记已修正（{{ selected.size }}）
        </Button>
      </div>
    </div>

    <!-- 筛选条：kbId 下拉 + docId 输入（后端仅两参，G2-G4） -->
    <div class="mb-4 flex flex-wrap items-center gap-2">
      <select
        data-testid="filter-kb"
        aria-label="按知识库筛选"
        :value="filters.kbId"
        class="h-9 rounded-lg border border-border bg-surface px-2 text-sm text-text outline-none transition-colors duration-150 focus:border-brand focus:ring-2 focus:ring-brand/20"
        @change="onFilterKbChange"
      >
        <option value="">全部知识库</option>
        <option v-for="kbItem in kbs" :key="kbItem.id" :value="kbItem.id">
          {{ kbItem.name }}
        </option>
      </select>
      <div class="flex items-center gap-2">
        <input
          v-model="docInput"
          data-testid="filter-doc"
          type="text"
          aria-label="按文档 ID 筛选"
          placeholder="输入文档 ID 过滤"
          class="h-9 w-56 rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
          @keyup.enter="applyDocFilter"
        />
        <Button variant="outline" size="sm" data-testid="apply-doc" @click="applyDocFilter">
          <PhMagnifyingGlass class="h-4 w-4" />
          应用
        </Button>
      </div>
    </div>

    <!-- 错误态：页内横幅 + 重试 -->
    <div
      v-if="listError"
      role="alert"
      class="flex items-center justify-between gap-4 rounded-lg border border-danger/30 bg-red-50 px-4 py-3"
    >
      <span class="text-sm text-danger">{{ listError }}</span>
      <Button variant="outline" size="sm" data-testid="retry-chunks" @click="refetch">重试</Button>
    </div>

    <!-- 加载态：表格骨架屏（与最终表格同形） -->
    <div
      v-else-if="isLoading"
      data-testid="chunk-skeleton"
      class="overflow-hidden rounded-xl border border-border bg-surface"
      aria-label="分片列表加载中"
    >
      <div class="flex items-center gap-6 border-b border-border bg-surface-2 px-4 py-2.5">
        <div
          v-for="i in 6"
          :key="`head-${i}`"
          class="h-3 w-20 animate-pulse rounded bg-slate-200"
        />
      </div>
      <div
        v-for="i in 5"
        :key="`row-${i}`"
        class="h-11 animate-pulse border-b border-border bg-slate-50"
      />
    </div>

    <!-- 空态：一句话（禁裸「暂无数据」） -->
    <div
      v-else-if="chunks.length === 0"
      class="flex flex-col items-center justify-center rounded-xl border border-dashed border-border bg-surface py-14 text-center"
    >
      <PhWarningCircle class="h-8 w-8 text-text-subtle" />
      <p class="mt-3 text-sm font-medium text-text">还没有待修正分片</p>
      <p class="mt-1 text-xs text-text-muted">现有分片全部完成修正，或尚无内容入库</p>
    </div>

    <!-- 正常态：分页表格（☐/内容预览/所属文档/集合类型/课程/操作） -->
    <template v-else>
      <div class="overflow-hidden rounded-xl border border-border bg-surface">
        <table data-testid="chunk-table" class="w-full text-sm">
          <thead class="border-b border-border bg-surface-2 text-left text-xs text-text-muted">
            <tr>
              <th class="w-10 px-2 text-center">
                <input
                  type="checkbox"
                  data-testid="select-all"
                  aria-label="全选当前页"
                  :checked="allSelected"
                  class="h-4 w-4 accent-brand"
                  @change="toggleAll"
                />
              </th>
              <th class="px-4 py-2.5 font-medium">内容预览</th>
              <th class="w-44 px-4 py-2.5 font-medium">所属文档</th>
              <th class="w-32 px-4 py-2.5 font-medium">集合类型</th>
              <th class="w-28 px-4 py-2.5 font-medium">课程</th>
              <th class="w-36 px-4 py-2.5 text-right font-medium">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="c in chunks"
              :key="c.id"
              :data-testid="`row-${c.id}`"
              class="h-11 border-b border-border last:border-b-0 transition-colors duration-150 hover:bg-surface-2"
            >
              <td class="px-2 text-center">
                <input
                  type="checkbox"
                  :data-testid="`select-${c.id}`"
                  aria-label="选择分片"
                  :checked="selected.has(c.id)"
                  class="h-4 w-4 accent-brand"
                  @change="toggleRow(c.id)"
                />
              </td>
              <!-- 内容预览：2 行截断 + 全文 tooltip（设计 §2.4.3） -->
              <td class="max-w-[320px] px-4">
                <p
                  :data-testid="`chunk-content-${c.id}`"
                  :title="c.content"
                  class="line-clamp-2 text-sm leading-relaxed text-text"
                >
                  {{ c.content }}
                </p>
              </td>
              <!-- 所属文档：docId 短格式（title 全文）+ kbId 原文小字（无文档名映射） -->
              <td :data-testid="`chunk-doc-${c.id}`" :title="c.docId" class="px-4">
                <p class="truncate font-medium text-text">{{ shortId(c.docId) }}</p>
                <p class="truncate text-xs text-text-subtle">{{ c.kbId }}</p>
              </td>
              <!-- collection_type Badge：TECHNICAL_QA 蓝 / COURSE_INFO 紫 / null 灰 -->
              <td class="px-4">
                <Badge
                  :data-testid="`chunk-collection-${c.id}`"
                  :variant="
                    c.collectionType === 'TECHNICAL_QA'
                      ? 'brand'
                      : c.collectionType === 'COURSE_INFO'
                        ? 'violet'
                        : 'default'
                  "
                >
                  {{ c.collectionType ?? '未分类' }}
                </Badge>
              </td>
              <!-- courseId：空或 'DEFAULT'（通用资料库真实形态）显「通用」灰 Badge，其余显 id 短格式 -->
              <td class="px-4">
                <Badge
                  v-if="!c.courseId || c.courseId === 'DEFAULT'"
                  :data-testid="`chunk-course-${c.id}`"
                  variant="default"
                >
                  通用
                </Badge>
                <span
                  v-else
                  :data-testid="`chunk-course-${c.id}`"
                  :title="c.courseId"
                  class="tabular-nums text-xs text-text-muted"
                >
                  {{ shortId(c.courseId) }}
                </span>
              </td>
              <td class="px-4 text-right">
                <div class="flex items-center justify-end gap-1">
                  <Button
                    variant="ghost"
                    size="sm"
                    :data-testid="`op-context-${c.id}`"
                    @click="openContext(c)"
                  >
                    <PhListDashes class="h-4 w-4" />
                    上下文
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    :data-testid="`op-edit-${c.id}`"
                    @click="openEdit(c)"
                  >
                    <PhPencilLine class="h-4 w-4" />
                    编辑
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
         批量修正 Dialog（480px）：collectionType 下拉 + courseId 课程搜索选择器
         ================================================================ -->
    <div
      v-if="batchDialogOpen"
      data-testid="batch-dialog"
      class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4"
      @keydown.esc="closeBatchDialog"
      @click.self="closeBatchDialog"
    >
      <div
        class="w-full max-w-[480px] rounded-xl border border-border bg-surface p-6 shadow-md"
        role="dialog"
        aria-modal="true"
        style="max-height: 85vh; overflow-y: auto"
        @click.stop
      >
        <h2 class="text-base font-semibold text-text">批量修正分片</h2>
        <!-- 数量与耗时提示：文档级 Milvus 同步可能慢（设计 §2.4.3 工作流第 3 步） -->
        <p class="mt-1 text-xs text-text-muted">
          将修正已勾选的 <span class="tabular-nums">{{ selected.size }}</span> 个分片，
          批量修正按文档级同步向量化，可能耗时较长
        </p>
        <div class="mt-5 space-y-4">
          <div>
            <label for="batch-collection-type" class="mb-1.5 block text-sm font-medium text-text">
              集合类型
            </label>
            <select
              id="batch-collection-type"
              v-model="batchCollectionType"
              data-testid="batch-collection-type"
              class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 focus:border-brand focus:ring-2 focus:ring-brand/20"
            >
              <option v-for="opt in COLLECTION_TYPE_OPTIONS" :key="opt.value" :value="opt.value">
                {{ opt.label }}
              </option>
            </select>
          </div>
          <div>
            <label for="batch-course-search" class="mb-1.5 block text-sm font-medium text-text">
              关联课程
            </label>
            <!-- 未选择时：输入框 + 固定「不改/通用(DEFAULT)」选项 + 远程搜索结果 -->
            <div v-if="batchCourseChoice.kind === 'keep'" class="relative">
              <input
                id="batch-course-search"
                v-model="batchCourseQuery"
                data-testid="batch-course-search"
                type="text"
                aria-label="搜索课程"
                placeholder="输入课程名搜索，或不改保持现状"
                class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
                @input="searchBatchCourses"
              />
              <ul
                data-testid="batch-course-results"
                class="mt-1 max-h-52 w-full overflow-auto rounded-lg border border-border bg-surface p-1 shadow-md"
              >
                <li>
                  <button
                    type="button"
                    data-testid="batch-course-keep"
                    class="w-full rounded-md px-3 py-1.5 text-left text-sm text-text transition-colors duration-150 hover:bg-surface-2"
                    @click="pickBatchCourse({ kind: 'keep' })"
                  >
                    不改（保持现有课程）
                  </button>
                </li>
                <li>
                  <button
                    type="button"
                    data-testid="batch-course-default"
                    class="w-full rounded-md px-3 py-1.5 text-left text-sm text-text transition-colors duration-150 hover:bg-surface-2"
                    @click="pickBatchCourse({ kind: 'default' })"
                  >
                    通用（DEFAULT）
                  </button>
                </li>
                <li v-for="c in batchCourseResults" :key="c.id">
                  <button
                    type="button"
                    :data-testid="`batch-course-option-${c.id}`"
                    class="w-full rounded-md px-3 py-1.5 text-left text-sm text-text transition-colors duration-150 hover:bg-surface-2"
                    @click="pickBatchCourse({ kind: 'course', course: c })"
                  >
                    {{ c.title }}
                  </button>
                </li>
                <li v-if="batchCourseSearching" class="px-3 py-1.5 text-xs text-text-subtle">
                  搜索中…
                </li>
              </ul>
            </div>
            <!-- 已选择：chip 展示 + 清除回「不改」 -->
            <div
              v-else
              :data-testid="
                batchCourseChoice.kind === 'default'
                  ? 'batch-course-picked-default'
                  : 'batch-course-picked'
              "
              class="flex items-center justify-between rounded-lg border border-border bg-surface-2 px-3 py-2 text-sm text-text"
            >
              <span>
                已选：
                {{
                  batchCourseChoice.kind === 'course'
                    ? batchCourseChoice.course.title
                    : '通用（DEFAULT）'
                }}
              </span>
              <button
                type="button"
                data-testid="batch-course-clear"
                aria-label="清除课程选择"
                class="text-text-muted transition-colors duration-150 hover:text-danger"
                @click="batchCourseChoice = { kind: 'keep' }"
              >
                <PhX class="h-4 w-4" />
              </button>
            </div>
          </div>
          <div class="flex justify-end gap-2 pt-2">
            <Button variant="outline" :disabled="batchSubmitting" @click="closeBatchDialog">
              取消
            </Button>
            <Button
              data-testid="submit-batch"
              :disabled="batchSubmitDisabled || batchSubmitting"
              @click="submitBatchUpdate"
            >
              <PhSpinnerGap v-if="batchSubmitting" class="h-4 w-4 animate-spin" />
              {{ batchSubmitting ? '提交中' : '确认修正' }}
            </Button>
          </div>
        </div>
      </div>
    </div>

    <!-- ================================================================
         标记已修正二次确认（danger 实底 + 不可撤销告警，设计 §2.6）
         ================================================================ -->
    <div
      v-if="correctedConfirmOpen"
      data-testid="corrected-dialog"
      class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4"
      @keydown.esc="closeCorrectedConfirm"
      @click.self="closeCorrectedConfirm"
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
            <h2 class="text-base font-semibold text-text">标记已修正</h2>
            <p class="mt-2 text-sm leading-relaxed text-text-muted">
              标记后分片将从待修正列表移除，
              <span class="font-medium text-danger">此操作不可撤销</span>。 确认标记已勾选的
              <span class="tabular-nums">{{ selected.size }}</span> 个分片？
            </p>
          </div>
        </div>
        <div class="mt-5 flex justify-end gap-2">
          <Button
            variant="outline"
            data-testid="cancel-corrected"
            :disabled="correctedSubmitting"
            @click="closeCorrectedConfirm"
          >
            取消
          </Button>
          <Button
            variant="danger"
            data-testid="confirm-corrected"
            :disabled="correctedSubmitting"
            @click="confirmBatchCorrected"
          >
            <PhSpinnerGap v-if="correctedSubmitting" class="h-4 w-4 animate-spin" />
            {{ correctedSubmitting ? '标记中' : '确认标记' }}
          </Button>
        </div>
      </div>
    </div>

    <!-- ================================================================
         编辑 Drawer（600px 右侧滑出：mono 全文 + 元数据只读 + 重向量化提示）
         ================================================================ -->
    <Transition name="fade">
      <div
        v-if="editTarget"
        class="fixed inset-0 z-50 bg-slate-900/40"
        data-testid="edit-overlay"
        @click="closeEdit"
      />
    </Transition>
    <Transition name="drawer-slide">
      <aside
        v-if="editTarget"
        data-testid="edit-drawer"
        role="dialog"
        aria-modal="true"
        class="fixed inset-y-0 right-0 z-50 flex w-[600px] max-w-full flex-col border-l border-border bg-surface shadow-md"
      >
        <!-- Drawer 头部：标题 + 关闭 -->
        <header class="flex items-center justify-between border-b border-border px-5 py-4">
          <div>
            <h2 class="text-base font-semibold text-text">编辑分片</h2>
            <p class="mt-0.5 text-xs text-text-muted">
              #<span class="tabular-nums">{{ editTarget.chunkIndex }}</span> ·
              {{ shortId(editTarget.id) }}
            </p>
          </div>
          <button
            type="button"
            data-testid="close-edit"
            aria-label="关闭编辑"
            class="rounded-md p-1 text-text-muted transition-colors duration-150 hover:bg-surface-2 hover:text-text"
            @click="closeEdit"
          >
            <PhX class="h-5 w-5" />
          </button>
        </header>
        <div class="flex-1 space-y-5 overflow-y-auto px-5 py-4">
          <div>
            <label for="edit-content" class="mb-1.5 block text-sm font-medium text-text">
              分片内容 <span class="text-danger">*</span>
            </label>
            <textarea
              id="edit-content"
              v-model="editContent"
              data-testid="edit-content"
              rows="12"
              aria-label="分片内容"
              class="h-72 w-full resize-y rounded-lg border border-border bg-surface px-3 py-2 font-mono text-sm leading-relaxed text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
            />
            <p v-if="editError" data-testid="edit-error" class="mt-1 text-xs text-danger">
              {{ editError }}
            </p>
          </div>
          <!-- 元数据只读区（设计 §2.4.3：headingPath/charOffset 起止/tokenCount） -->
          <dl
            data-testid="edit-meta"
            class="rounded-lg border border-border bg-surface-2 p-4 text-sm"
          >
            <div class="flex items-start justify-between gap-4 border-b border-border pb-2">
              <dt class="shrink-0 text-xs text-text-subtle">章节路径</dt>
              <dd class="break-all text-right text-text">{{ editTarget.headingPath || '无' }}</dd>
            </div>
            <div class="flex items-center justify-between gap-4 border-b border-border py-2">
              <dt class="text-xs text-text-subtle">字符偏移</dt>
              <dd class="tabular-nums text-text">
                {{ editTarget.charOffsetStart }} - {{ editTarget.charOffsetEnd }}
              </dd>
            </div>
            <div class="flex items-center justify-between gap-4 pt-2">
              <dt class="text-xs text-text-subtle">Token 数</dt>
              <dd class="tabular-nums text-text">{{ editTarget.tokenCount }}</dd>
            </div>
          </dl>
          <!-- 保存语义提示：改内容触发重新向量化（设计 §2.4.3） -->
          <p class="text-xs leading-relaxed text-text-muted">
            保存后内容变更会触发该文档重新向量化，检索结果稍后生效
          </p>
        </div>
        <footer class="flex justify-end gap-2 border-t border-border px-5 py-4">
          <Button
            variant="outline"
            :disabled="editSaving"
            data-testid="cancel-edit"
            @click="closeEdit"
          >
            取消
          </Button>
          <Button data-testid="submit-edit" :disabled="editSaving" @click="submitEdit">
            <PhSpinnerGap v-if="editSaving" class="h-4 w-4 animate-spin" />
            {{ editSaving ? '保存中' : '保存' }}
          </Button>
        </footer>
      </aside>
    </Transition>

    <!-- ================================================================
         上下文 Drawer（600px：parent/prev/current/next 时间线，null 不渲染）
         ================================================================ -->
    <Transition name="fade">
      <div
        v-if="contextOpen"
        class="fixed inset-0 z-50 bg-slate-900/40"
        data-testid="context-overlay"
        @click="closeContext"
      />
    </Transition>
    <Transition name="drawer-slide">
      <aside
        v-if="contextOpen"
        data-testid="context-drawer"
        role="dialog"
        aria-modal="true"
        class="fixed inset-y-0 right-0 z-50 flex w-[600px] max-w-full flex-col border-l border-border bg-surface shadow-md"
      >
        <header class="flex items-center justify-between border-b border-border px-5 py-4">
          <div>
            <h2 class="text-base font-semibold text-text">分片上下文</h2>
            <p class="mt-0.5 text-xs text-text-muted">父分片 → 前一分片 → 当前分片 → 下一分片</p>
          </div>
          <button
            type="button"
            data-testid="close-context"
            aria-label="关闭上下文"
            class="rounded-md p-1 text-text-muted transition-colors duration-150 hover:bg-surface-2 hover:text-text"
            @click="closeContext"
          >
            <PhX class="h-5 w-5" />
          </button>
        </header>
        <div class="flex-1 overflow-y-auto px-5 py-5">
          <!-- 加载态 -->
          <div
            v-if="contextLoading"
            data-testid="ctx-loading"
            class="flex items-center gap-2 py-10 text-sm text-text-muted"
          >
            <PhSpinnerGap class="h-4 w-4 animate-spin" />
            正在加载上下文
          </div>
          <!-- 错误态：文案 + 重试 -->
          <div v-else-if="contextError" data-testid="ctx-error" class="py-10 text-center">
            <p class="text-sm text-danger">{{ contextError }}</p>
            <Button
              variant="outline"
              size="sm"
              data-testid="retry-ctx"
              class="mt-3"
              @click="loadContext(contextChunkId)"
            >
              重试
            </Button>
          </div>
          <!-- 时间线：左轨 + 节点卡，null 节点已过滤不渲染 -->
          <ol v-else class="relative ml-2 space-y-6 border-l-2 border-border pl-6">
            <li
              v-for="node in contextNodes"
              :key="node.key"
              :data-testid="`ctx-${node.key}`"
              class="relative"
            >
              <!-- 轨上圆点 -->
              <span
                aria-hidden="true"
                class="absolute -left-[31px] top-1.5 h-3 w-3 rounded-full border-2 border-brand bg-surface"
              />
              <p class="text-xs font-medium text-text-muted">{{ node.label }}</p>
              <p class="mt-1 truncate text-sm font-medium text-text">
                {{ node.chunk.headingPath || `第 ${node.chunk.chunkIndex} 片` }}
              </p>
              <p class="mt-1 line-clamp-2 text-sm leading-relaxed text-text">
                {{ node.chunk.content }}
              </p>
              <p class="mt-1 tabular-nums text-xs text-text-subtle">
                #{{ node.chunk.chunkIndex }} · {{ shortId(node.chunk.id) }} ·
                {{ node.chunk.startPage }}-{{ node.chunk.endPage }} 页
              </p>
            </li>
          </ol>
        </div>
      </aside>
    </Transition>
  </main>
</template>

<style scoped>
/**
 * Drawer 滑入动效（设计 §2.2：MOTION 2，仅 transform/opacity，200ms）
 * fade 作用于遮罩淡入淡出；drawer-slide 作用于面板右滑
 */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 200ms ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
.drawer-slide-enter-active,
.drawer-slide-leave-active {
  transition:
    transform 200ms ease,
    opacity 200ms ease;
}
.drawer-slide-enter-from,
.drawer-slide-leave-to {
  transform: translateX(100%);
  opacity: 0;
}
</style>
