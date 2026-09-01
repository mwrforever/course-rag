<script lang="ts">
/**
 * 文档管理页（设计 §2.4.2）
 *
 * 能力清单：
 * 1. 列表表格：☐ 多选 / 文件名（+所属库小字）/ 类型 Badge / 状态 Badge（8 态）/
 *    分片数 / 上传时间（相对 + 绝对 tooltip）/ 更新时间 / 操作 ⋮ 菜单
 * 2. 筛选：kbId 下拉 + status 下拉 + q 关键字（搜索按钮/回车提交）
 * 3. 排序仅 created/updated 两值（后端实测固定降序），排序指示器只在这两列
 * 4. 上传 Dialog：kbId 必选下拉 + courseId 可选搜索选择器 + 拖拽区
 *    （白名单文案由 UPLOAD_FILE_TYPES / UPLOAD_MAX_SIZE_MB 派生，单一事实源不漂移）
 *    + 标题 + XHR 进度条；完成关闭并刷新
 * 5. 批量删除：无后端批量端点（设计 D10）→ 前端循环单条 + Promise.allSettled
 *    聚合 toast「成功 n / 失败 m」
 * 6. ETL 轮询：vue-query refetchInterval 由 useEtlPolling 决策（非终态 5s / 全终态停）
 *
 * 契约要点：id/total 为 Long 字符串铁律；page/size 为 number；时间 ISO-8601。
 * 四态：loading 骨架 / empty 含行动入口 / error 横幅重试 / 正常。
 *
 * 线程安全注意：全部状态为组件私有 ref，无跨实例共享可变状态。
 */

/** 上传文件类型白名单（设计 §2.4.2：B 端白名单，禁与 C 端附件白名单混用；2026-08-30 扩展 Excel XLSX/XLS） */
export const UPLOAD_FILE_TYPES = ['pdf', 'docx', 'pptx', 'md', 'txt', 'xlsx', 'xls'] as const

/** 上传大小上限（MB，设计 §2.4.2 ≤100MB；与后端 etl.max-file-size-mb 配置同值） */
export const UPLOAD_MAX_SIZE_MB = 100

/**
 * 上传文件合法性校验（类型白名单 + 大小上限）
 *
 * @param name 文件名（用于提取扩展名，小写比对）
 * @param size 文件字节数
 * @returns 空串表示合法；否则返回中文错误文案（就地展示在表单下方）
 */
export function validateUploadFile(name: string, size: number): string {
  const dot = name.lastIndexOf('.')
  const ext = dot >= 0 ? name.slice(dot + 1).toLowerCase() : ''
  if (!(UPLOAD_FILE_TYPES as readonly string[]).includes(ext)) {
    return `不支持的文件类型：仅支持 ${UPLOAD_FILE_TYPES.join('/')} 文件`
  }
  if (size > UPLOAD_MAX_SIZE_MB * 1024 * 1024) {
    return `文件大小超过 ${UPLOAD_MAX_SIZE_MB}MB 上限`
  }
  return ''
}
</script>

<script setup lang="ts">
/**
 * 文档管理页主逻辑（vue-query 数据源 + ETL 轮询 + 批量 allSettled + 上传进度）
 */
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import {
  PhArrowClockwise,
  PhArrowDown,
  PhDotsThreeVertical,
  PhDownloadSimple,
  PhMagnifyingGlass,
  PhPencilSimple,
  PhRepeat,
  PhSpinnerGap,
  PhTrash,
  PhUploadSimple,
  PhWarningCircle,
} from '@phosphor-icons/vue'

import EtlStatusBadge from '@/components/EtlStatusBadge.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { DataTable } from '@/components/ui/data-table'
import { DropdownMenuItem } from '@/components/ui/dropdown-menu'
import { EmptyState } from '@/components/ui/empty-state'
import { IconButton } from '@/components/ui/icon-button'
import { PageHead } from '@/components/ui/page-head'
import { RemoteSelect } from '@/components/ui/remote-select'
import { useEtlPolling } from '@/composables/use-etl-polling'
import { ApiError, courseApi, documentApi, knowledgeBaseApi } from '@/lib/api'
import { showToast } from '@/lib/toast'
import { formatDateTime, formatRelativeTime } from '@/lib/utils'

import type { CourseDTO, DocumentParseStatus, DocumentVO, KnowledgeBaseVO } from '@/lib/types'

/** 每页条数（设计 §2.6 分页器） */
const PAGE_SIZE = 10

/** 状态筛选选项（设计 §2.5 八态全量） */
const STATUS_OPTIONS: Array<{ value: DocumentParseStatus; label: string }> = [
  { value: 'PENDING', label: '排队中' },
  { value: 'PARSING', label: '解析中' },
  { value: 'PARSED', label: '解析完成' },
  { value: 'CHUNKING', label: '分片中' },
  { value: 'CHUNKED', label: '分片完成' },
  { value: 'EMBEDDING', label: '向量化中' },
  { value: 'INDEXED', label: '已入库（终态）' },
  { value: 'FAILED', label: '失败（终态）' },
]

const router = useRouter()

// ====================================================================
// 列表数据（vue-query + ETL 轮询）
// ====================================================================

/** 筛选条件（kbId/status 变更即触发查询；q 仅搜索按钮提交） */
const filters = reactive({ kbId: '', status: '', q: '' })
/** q 输入框草稿：提交前不写入 filters */
const qInput = ref('')
const page = ref(1)
/** 排序值：仅 created/updated（后端实测固定降序，排序指示器只在这两列） */
const sort = ref<'created' | 'updated'>('created')

/**
 * 当前页记录镜像：queryFn 每次成功回写，ETL 轮询间隔判定读取。
 * 之所以不走 query.data 直接派生：useEtlPolling 需在 useQuery 选项内声明，
 * 直接引用 query 会触发 TDZ 自引用，故以镜像 ref 解耦（无副作用，仅读）。
 */
const records = ref<DocumentVO[]>([])

/** 查询参数构造：空筛选值不携带（axios 端 undefined 参数同样会被忽略） */
function buildListParams() {
  return {
    ...(filters.kbId ? { kbId: filters.kbId } : {}),
    ...(filters.status ? { status: filters.status } : {}),
    ...(filters.q ? { q: filters.q } : {}),
    sort: sort.value,
    page: page.value,
    size: PAGE_SIZE,
  }
}

/** 查询键：筛选/页码/排序任一变化即触发新查询 */
const queryKey = computed(() => [
  'admin-documents',
  { kbId: filters.kbId, status: filters.status, q: filters.q },
  page.value,
  sort.value,
])

const {
  data,
  isLoading,
  isError,
  isFetching,
  error: queryError,
  refetch,
} = useQuery({
  queryKey,
  queryFn: async () => {
    const res = await documentApi.list(buildListParams())
    records.value = res.records ?? []
    return res
  },
  // ETL 轮询：列表存在非终态行 → 5000ms 自动刷新；全终态 → false 停止
  refetchInterval: useEtlPolling(records),
})

const docs = computed(() => data.value?.records ?? [])
const total = computed(() => data.value?.total ?? '0')
const totalPages = computed(() => Math.max(1, Math.ceil(Number(total.value) / PAGE_SIZE)))

/** 接口错误分级文案（ApiError 透出 message，503 统一降级；非 ApiError 兜底） */
function messageOf(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    return err.code === 503 ? '服务暂时不可用，请稍后重试' : err.message
  }
  return fallback
}

const listError = computed(() =>
  isError.value ? messageOf(queryError.value, '文档列表加载失败，请稍后重试') : '',
)

/** 知识库选项（筛选下拉 + 所属库小字映射 + 上传下拉），加载失败不阻塞列表（查询错误仅空数组） */
const { data: kbsData } = useQuery({
  queryKey: ['admin-kbs-options'],
  queryFn: () => knowledgeBaseApi.list({ page: 1, size: 100 }),
})
const kbs = computed(() => kbsData.value?.records ?? [])
const kbNameOf = computed(() => {
  const map = new Map(kbs.value.map((k) => [k.id, k.name]))
  return (id: string) => map.get(id) ?? id
})

onMounted(() => {
  // 滚动收起行菜单（fixed 菜单不随滚动，滚动后收起避免悬空）
  window.addEventListener('scroll', onWindowScroll, { capture: true, passive: true })
})

onUnmounted(() => {
  window.removeEventListener('scroll', onWindowScroll, { capture: true } as EventListenerOptions)
})

// ---- 筛选与分页 ----

/** kbId 筛选变更：重置页码第 1 页（查询键变化自动触发拉取） */
function onFilterKbChange(e: Event) {
  filters.kbId = (e.target as HTMLSelectElement).value
  page.value = 1
}

/** status 筛选变更：重置页码第 1 页 */
function onFilterStatusChange(e: Event) {
  filters.status = (e.target as HTMLSelectElement).value
  page.value = 1
}

/** q 关键字提交（搜索按钮 / 回车）：去空白后写入 filters 触发查询 */
function applyKeyword() {
  filters.q = qInput.value.trim()
  page.value = 1
}

/** 切换排序值：点击表头在 created/updated 间切换（后端固定降序，无升序） */
function changeSort(key: 'created' | 'updated') {
  if (sort.value !== key) {
    sort.value = key
  }
}

/** 翻页：越界保护（禁用态按钮兜底） */
function changePage(next: number) {
  if (next < 1 || next > totalPages.value) return
  page.value = next
}

// ====================================================================
// 多选与批量删除（设计 D10：无批量端点 → 循环单条 + allSettled 聚合）
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
  const allChecked = docs.value.length > 0 && docs.value.every((d) => selected.value.has(d.id))
  selected.value = allChecked ? new Set() : new Set(docs.value.map((d) => d.id))
}

const allSelected = computed(
  () => docs.value.length > 0 && docs.value.every((d) => selected.value.has(d.id)),
)

const batchConfirmOpen = ref(false)

const queryClient = useQueryClient()

/**
 * 批量删除提交（mutationFn 内循环单条 remove + Promise.allSettled 聚合）
 *
 * 聚合文案「成功 n / 失败 m」（全部成功 success 色，存在失败 danger 色）；
 * 完成后清空勾选并失效列表键（失败行保留在列表内，用户可单独重试）。
 * 勾选覆盖当前页全部行且全部成功时，删除会留下空页——回退一页防空页（与单条删除同语义）。
 */
const { isPending: batchDeleting, mutate: batchDeleteMutation } = useMutation({
  mutationFn: async (ids: string[]) => {
    const results = await Promise.allSettled(ids.map((id) => documentApi.remove(id)))
    const failed = results.filter((r) => r.status === 'rejected').length
    return { succeeded: ids.length - failed, failed }
  },
  onSuccess: (outcome, ids) => {
    showToast(
      `成功 ${outcome.succeeded} / 失败 ${outcome.failed}`,
      outcome.failed > 0 ? 'danger' : 'success',
    )
    batchConfirmOpen.value = false
    selected.value = new Set()
    if (
      outcome.failed === 0 &&
      docs.value.length > 0 &&
      docs.value.every((d) => ids.includes(d.id)) &&
      page.value > 1
    ) {
      page.value -= 1
    } else {
      queryClient.invalidateQueries({ queryKey: ['admin-documents'] })
    }
  },
})

/** 批量删除：勾选非空校验 → 走 mutation（完成/失败由 onSuccess/onError 处理） */
function confirmBatchDelete() {
  const ids = [...selected.value]
  if (ids.length === 0) return
  batchDeleteMutation(ids)
}

// ====================================================================
// 行操作 ⋮ 菜单（查看分片 / 重新解析 / 下载 / 改标题 / 删除）
// ====================================================================

/**
 * 当前展开菜单（行 id + 触发按钮右下角坐标）
 *
 * 菜单 Teleport 到 body 并 fixed 定位：表格容器 overflow-hidden 会裁切行内
 * absolute 菜单（末行展开最明显），移出裁切上下文后任何行均完整可见
 * （e2e「末行菜单完整可见」几何断言依赖此挂载方式，禁止改为行内弹层）。
 */
const openMenu = ref<{ id: string; x: number; y: number } | null>(null)

function toggleMenu(id: string, event: MouseEvent) {
  // 再次点击同一行 = 收起
  if (openMenu.value && openMenu.value.id === id) {
    openMenu.value = null
    return
  }
  // 记录按钮视口坐标（右下角）：菜单以此为 fixed 定位锚点，菜单右缘贴合按钮右缘
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  openMenu.value = { id, x: rect.right, y: rect.bottom }
}

function closeMenu() {
  openMenu.value = null
}

/** 页面滚动时收起菜单：fixed 菜单不随滚动迁移，避免悬空漂移 */
function onWindowScroll() {
  if (openMenu.value) openMenu.value = null
}

/** 查看分片：跳转文档详情页（分片列表在详情页） */
function viewChunks(doc: DocumentVO) {
  closeMenu()
  router.push({ name: 'knowledge-document-detail', params: { id: doc.id } })
}

/** 重新解析：重置 ETL 管道（FAILED 恢复入口，其余状态同样支持）；成功后失效列表键触发轮询 */
const { mutate: reparseMutation } = useMutation({
  mutationFn: (id: string) => documentApi.reparse(id),
  onSuccess: () => {
    showToast('已重新解析，稍后查看最新状态', 'success')
    queryClient.invalidateQueries({ queryKey: ['admin-documents'] })
  },
  onError: (err) => {
    showToast(messageOf(err, '重新解析失败，请稍后重试'), 'danger')
  },
})

/** 重新解析入口：收起行菜单后提交 */
function handleReparse(doc: DocumentVO) {
  closeMenu()
  reparseMutation(doc.id)
}

/** 下载原始文件：blob → 本地锚点落盘（文件名取原始标题） */
async function handleDownload(doc: DocumentVO) {
  closeMenu()
  try {
    const blob = await documentApi.download(doc.id)
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = doc.title
    anchor.click()
    URL.revokeObjectURL(url)
    showToast('文件已开始下载', 'success')
  } catch (err) {
    showToast(messageOf(err, '下载失败，请稍后重试'), 'danger')
  }
}

// ---- 改标题 Dialog ----

const renameTarget = ref<DocumentVO | null>(null)
const renameTitle = ref('')
const renameError = ref('')

function openRename(doc: DocumentVO) {
  closeMenu()
  renameTarget.value = doc
  renameTitle.value = doc.title
  renameError.value = ''
}

function closeRename() {
  if (renameSubmitting.value) return
  renameTarget.value = null
}

/** 保存标题：必填校验 → mutation → toast → 关闭并失效列表键 */
const { isPending: renameSubmitting, mutate: submitRenameMutation } = useMutation({
  mutationFn: (payload: { id: string; title: string }) =>
    documentApi.update(payload.id, { title: payload.title }),
  onSuccess: () => {
    showToast('标题已更新', 'success')
    // 直接置空关闭（不经 closeRename：其提交中拦截会挡住 isPending 期间的 onSuccess）
    renameTarget.value = null
    queryClient.invalidateQueries({ queryKey: ['admin-documents'] })
  },
  onError: (err) => {
    showToast(messageOf(err, '保存失败，请稍后重试'), 'danger')
  },
})

function submitRename() {
  if (!renameTarget.value) return
  if (!renameTitle.value.trim()) {
    renameError.value = '请输入标题'
    return
  }
  submitRenameMutation({ id: renameTarget.value.id, title: renameTitle.value.trim() })
}

// ---- 单条删除二次确认 ----

const deletingDoc = ref<DocumentVO | null>(null)

function requestDelete(doc: DocumentVO) {
  closeMenu()
  deletingDoc.value = doc
}

function cancelDelete() {
  if (deletingLoading.value) return
  deletingDoc.value = null
}

/**
 * 确认删除：remove → toast → 关闭确认框 → 勾选集同步移除该行 → 失效列表键
 *
 * 删除末页最后一条会留下空页：回退一页（页码变化自动重拉）；否则失效当前列表键。
 */
const { isPending: deletingLoading, mutate: confirmDeleteMutation } = useMutation({
  mutationFn: (id: string) => documentApi.remove(id),
  onSuccess: (_data, id) => {
    showToast('文档已删除', 'success')
    deletingDoc.value = null
    // 勾选集同步移除已删行（单条删除不残留勾选，批量按钮计数即时归零）
    selected.value = new Set([...selected.value].filter((s) => s !== id))
    if (docs.value.length === 1 && page.value > 1) {
      page.value -= 1
    } else {
      queryClient.invalidateQueries({ queryKey: ['admin-documents'] })
    }
  },
  onError: (err) => {
    showToast(messageOf(err, '删除失败，请稍后重试'), 'danger')
  },
})

function confirmDelete() {
  if (!deletingDoc.value) return
  confirmDeleteMutation(deletingDoc.value.id)
}

// ====================================================================
// 上传 Dialog（kbId 必选 + courseId 可选 + 拖拽区 + XHR 进度条）
// ====================================================================

const uploadOpen = ref(false)
/** 已选知识库（remote-select 单选，modelValue 承载选项对象；null = 未选） */
const uploadKb = ref<KnowledgeBaseVO | null>(null)
const uploadTitle = ref('')
const uploadFile = ref<File | null>(null)
const uploadCourse = ref<CourseDTO | null>(null)
const uploadError = ref('')
/** 上传进度百分比（XHR onUploadProgress 回调驱动；本地瞬时 UI 状态，不进 query 缓存） */
const progress = ref(0)
const fileInputRef = ref<HTMLInputElement | null>(null)

/**
 * 知识库远程搜索 fetcher（remote-select 契约 E：防抖 300ms + AbortController 取消）
 *
 * @param keyword 搜索关键字（空串 = 首屏候选）
 * @param signal 取消信号（透传 api 层，新输入取消旧请求）
 * @returns 命中的知识库列表
 */
async function fetchKbs(keyword: string, signal: AbortSignal): Promise<KnowledgeBaseVO[]> {
  const res = await knowledgeBaseApi.list({ page: 1, size: 100, keyword, signal })
  return res.records ?? []
}

/**
 * 课程远程搜索 fetcher（可选关联课程；契约 E：防抖 + 取消由组件负责）
 *
 * @param keyword 搜索关键字（空串 = 首屏候选）
 * @param signal 取消信号
 * @returns 命中的课程列表（size 10）
 */
async function fetchCourses(keyword: string, signal: AbortSignal): Promise<CourseDTO[]> {
  const res = await courseApi.list({ keyword, size: 10, signal })
  return res.records ?? []
}

/**
 * 知识库选中变化（单选；联合类型按首元素归一收窄）
 *
 * @param value remote-select 回抛的选中值
 */
function onUploadKbSelect(value: KnowledgeBaseVO | KnowledgeBaseVO[] | null) {
  uploadKb.value = Array.isArray(value) ? (value[0] ?? null) : value
}

/**
 * 课程选中变化（单选，null = 不关联）
 *
 * @param value remote-select 回抛的选中值
 */
function onUploadCourseSelect(value: CourseDTO | CourseDTO[] | null) {
  uploadCourse.value = Array.isArray(value) ? (value[0] ?? null) : value
}

function openUpload() {
  uploadOpen.value = true
  uploadError.value = ''
}

function closeUpload() {
  if (uploading.value) return
  uploadOpen.value = false
  resetUpload()
}

/** 重置上传表单（关闭 / 成功后清理，避免残留上次选择） */
function resetUpload() {
  uploadKb.value = null
  uploadTitle.value = ''
  uploadFile.value = null
  uploadCourse.value = null
  uploadError.value = ''
  progress.value = 0
}

/** 拖拽区点击 → 唤起文件选择 */
function openFilePicker() {
  fileInputRef.value?.click()
}

/** 文件选择变化：记录文件并回显（实际校验在提交时统一执行） */
function onFileChange(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0] ?? null
  if (file) {
    uploadFile.value = file
  }
}

/** 拖拽落下：取首个文件（拖拽区 drag 语义，prevent 由模板处理） */
function onDrop(e: DragEvent) {
  const file = e.dataTransfer?.files?.[0] ?? null
  if (file) {
    uploadFile.value = file
  }
}

/** 上传表单校验：kbId 必选 → 标题必填 → 文件必选 → 类型/大小白名单 */
function validateUpload(): string {
  if (!uploadKb.value) return '请选择知识库'
  if (!uploadTitle.value.trim()) return '请输入标题'
  if (!uploadFile.value) return '请选择文件'
  return validateUploadFile(uploadFile.value.name, uploadFile.value.size)
}

/**
 * 上传提交：校验 → FormData（kbId/title/file 必传，courseId 选中才携带）→ mutation
 *
 * mutationFn 内 documentApi.upload 携带进度回调（XHR onUploadProgress → 本地 progress ref，
 * 驱动进度条宽度）；isPending 顶层解构为 uploading（F1 实证：嵌套访问 ref 不自动解包）。
 * 成功后 toast、关闭 Dialog、重置表单并失效列表键（新文档 PENDING 进入轮询）。
 */
const { isPending: uploading, mutate: submitUploadMutation } = useMutation({
  mutationFn: (form: FormData) =>
    documentApi.upload(form, (p) => {
      progress.value = p
    }),
  onSuccess: () => {
    showToast('上传成功，正在解析', 'success')
    uploadOpen.value = false
    resetUpload()
    queryClient.invalidateQueries({ queryKey: ['admin-documents'] })
  },
  onError: (err) => {
    showToast(messageOf(err, '上传失败，请稍后重试'), 'danger')
  },
})

function submitUpload() {
  const invalid = validateUpload()
  if (invalid) {
    uploadError.value = invalid
    return
  }
  // validateUpload 已保证非空，此处局部收窄供 FormData 取值（跨函数调用 TS 无法保持收窄）
  const kb = uploadKb.value
  const file = uploadFile.value
  if (!kb || !file) return
  uploadError.value = ''
  progress.value = 0
  const form = new FormData()
  form.set('kbId', kb.id)
  form.set('title', uploadTitle.value.trim())
  if (uploadCourse.value) {
    form.set('courseId', uploadCourse.value.id)
  }
  form.set('file', file)
  submitUploadMutation(form)
}
</script>

<template>
  <!-- 页头（设计稿 .page-head）：主标题 + 副题 + 右侧动作区（知识库入口 / 批量删除 / 上传） -->
  <PageHead title="文档管理" subtitle="管理知识库文档与解析状态">
    <template #actions>
      <!-- 手动刷新（T2.3）：refetch 期间禁用防重复 -->
      <IconButton label="刷新" data-testid="refresh-docs" :loading="isFetching" @click="refetch()">
        <PhArrowClockwise class="h-4 w-4" />
      </IconButton>
      <router-link
        to="/knowledge-bases"
        data-testid="manage-kbs"
        class="inline-flex h-9 items-center rounded-lg border border-border bg-surface px-4 text-sm font-medium text-text transition-colors duration-150 hover:bg-surface-2"
      >
        管理知识库
      </router-link>
      <!-- 批量删除：勾选后出现（计数随勾选集联动） -->
      <Button
        v-if="selected.size > 0"
        variant="danger"
        size="sm"
        data-testid="batch-delete"
        @click="batchConfirmOpen = true"
      >
        <PhTrash class="h-4 w-4" />
        批量删除（{{ selected.size }}）
      </Button>
      <Button data-testid="upload-doc" @click="openUpload">
        <PhUploadSimple class="h-4 w-4" />
        上传文档
      </Button>
    </template>
  </PageHead>

  <!-- 筛选条（白卡收纳）：kbId / status / q（搜索按钮或回车提交） -->
  <div
    v-reveal
    class="mt-5 mb-4 flex flex-wrap items-center gap-2 rounded-2xl border border-border bg-surface p-3 shadow-xs"
  >
    <select
      data-testid="filter-kb"
      aria-label="按知识库筛选"
      :value="filters.kbId"
      class="h-9 rounded-xl border border-border bg-surface px-2.5 text-sm text-text outline-none transition-colors duration-150 focus:border-brand focus:ring-2 focus:ring-brand/20"
      @change="onFilterKbChange"
    >
      <option value="">全部知识库</option>
      <option v-for="kbItem in kbs" :key="kbItem.id" :value="kbItem.id">
        {{ kbItem.name }}
      </option>
    </select>
    <select
      data-testid="filter-status"
      aria-label="按状态筛选"
      :value="filters.status"
      class="h-9 rounded-xl border border-border bg-surface px-2.5 text-sm text-text outline-none transition-colors duration-150 focus:border-brand focus:ring-2 focus:ring-brand/20"
      @change="onFilterStatusChange"
    >
      <option value="">全部状态</option>
      <option v-for="opt in STATUS_OPTIONS" :key="opt.value" :value="opt.value">
        {{ opt.label }}
      </option>
    </select>
    <div class="flex items-center gap-2">
      <input
        v-model="qInput"
        data-testid="filter-q"
        type="text"
        aria-label="按文件名搜索"
        placeholder="搜索文件名"
        class="h-9 w-56 rounded-xl border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
        @keyup.enter="applyKeyword"
      />
      <Button variant="outline" size="sm" data-testid="apply-q" @click="applyKeyword">
        <PhMagnifyingGlass class="h-4 w-4" />
        搜索
      </Button>
    </div>
  </div>

  <!-- 错误态：页内横幅 + 重试（设计 §1.7） -->
  <div
    v-if="listError"
    role="alert"
    class="flex items-center justify-between gap-4 rounded-xl border border-danger/30 bg-red-50 px-4 py-3"
  >
    <span class="text-sm text-danger">{{ listError }}</span>
    <Button variant="outline" size="sm" data-testid="retry-docs" @click="refetch">重试</Button>
  </div>

  <!-- 加载态：表格骨架屏（表头 + 5 行灰条，与最终表格同形） -->
  <div
    v-else-if="isLoading"
    data-testid="doc-skeleton"
    class="overflow-hidden rounded-2xl border border-border bg-surface shadow-xs"
    aria-label="文档列表加载中"
  >
    <div class="flex items-center gap-6 border-b border-border bg-surface-2 px-5 py-3.5">
      <div v-for="i in 7" :key="`head-${i}`" class="h-3 w-20 animate-pulse rounded bg-slate-200" />
    </div>
    <div
      v-for="i in 5"
      :key="`row-${i}`"
      class="h-12 animate-pulse border-b border-border bg-slate-50 last:border-b-0"
    />
  </div>

  <!-- 空态（EmptyState 标准结构）：一句话 + 上传行动入口（禁裸「暂无数据」） -->
  <div
    v-else-if="docs.length === 0"
    v-reveal
    class="rounded-2xl border border-border bg-surface shadow-xs"
  >
    <EmptyState title="还没有文档" description="上传后自动进入解析管道，可实时查看进度">
      <template #icon>
        <PhUploadSimple class="h-6 w-6" aria-hidden="true" />
      </template>
      <template #action>
        <Button data-testid="upload-doc-empty" @click="openUpload">
          <PhUploadSimple class="h-4 w-4" />
          上传文档
        </Button>
      </template>
    </EmptyState>
  </div>

  <!-- 正常态：分页表格（列：☐/文件名/类型/状态/分片数/上传时间/更新时间/操作） -->
  <template v-else>
    <!-- 表格卡：overflow-hidden 裁出圆角表头（行菜单已 Teleport 到 body，不受容器裁切影响） -->
    <div
      v-reveal="80"
      data-testid="doc-table-container"
      class="overflow-hidden rounded-2xl border border-border bg-surface shadow-xs"
    >
      <DataTable data-testid="doc-table" label="文档列表">
        <template #header>
          <tr>
            <th class="w-10 px-2 text-center">
              <!-- 全选：:checked 由 allSelected 计算驱动，@change 切换（无需 v-model） -->
              <input
                type="checkbox"
                data-testid="select-all"
                aria-label="全选当前页"
                :checked="allSelected"
                class="h-4 w-4 accent-brand"
                @change="toggleAll"
              />
            </th>
            <th>文件名</th>
            <th>类型</th>
            <th>状态</th>
            <th class="text-right">分片数</th>
            <!-- 排序指示器仅 created/updated 两列启用的表头（后端实测两值） -->
            <th>
              <button
                type="button"
                data-testid="sort-created"
                class="inline-flex items-center gap-1 transition-colors duration-150 hover:text-text"
                @click="changeSort('created')"
              >
                上传时间
                <PhArrowDown
                  v-if="sort === 'created'"
                  class="h-3 w-3 text-brand"
                  aria-label="按上传时间排序"
                />
              </button>
            </th>
            <th>
              <button
                type="button"
                data-testid="sort-updated"
                class="inline-flex items-center gap-1 transition-colors duration-150 hover:text-text"
                @click="changeSort('updated')"
              >
                更新时间
                <PhArrowDown
                  v-if="sort === 'updated'"
                  class="h-3 w-3 text-brand"
                  aria-label="按更新时间排序"
                />
              </button>
            </th>
            <th class="w-16 text-right">操作</th>
          </tr>
        </template>
        <tr v-for="docItem in docs" :key="docItem.id" :data-testid="`row-${docItem.id}`">
          <td class="px-2 text-center">
            <input
              type="checkbox"
              :data-testid="`select-${docItem.id}`"
              aria-label="选择文档"
              :checked="selected.has(docItem.id)"
              class="h-4 w-4 accent-brand"
              @change="toggleRow(docItem.id)"
            />
          </td>
          <td class="max-w-[240px]">
            <p class="truncate font-semibold text-text" :title="docItem.title">
              {{ docItem.title }}
            </p>
            <!-- 所属库小字（设计 §2.4.2：文件名 + 所属库） -->
            <p class="mt-0.5 truncate text-xs text-text-subtle">{{ kbNameOf(docItem.kbId) }}</p>
          </td>
          <td>
            <Badge variant="default">{{ docItem.fileType.toUpperCase() }}</Badge>
          </td>
          <td>
            <EtlStatusBadge :status="docItem.parseStatus" :error-message="docItem.errorMessage" />
          </td>
          <td class="text-right tabular-nums">{{ docItem.chunkCount }}</td>
          <!-- 上传时间：相对展示 + 绝对时间 tooltip（设计 §2.4.2） -->
          <td
            :data-testid="`doc-time-${docItem.id}`"
            :title="formatDateTime(docItem.createdAt)"
            class="tabular-nums"
          >
            {{ formatRelativeTime(docItem.createdAt) }}
          </td>
          <td :title="formatDateTime(docItem.updatedAt)" class="tabular-nums">
            {{ formatRelativeTime(docItem.updatedAt) }}
          </td>
          <td class="text-right">
            <!-- ⋮ 触发钮（设计稿 eye-btn 圆形操作钮形态）：hover 品牌实底 -->
            <button
              type="button"
              :data-testid="`doc-menu-${docItem.id}`"
              aria-label="文档操作"
              class="inline-grid h-[34px] w-[34px] place-items-center rounded-full bg-brand-soft text-text transition-all duration-200 hover:bg-brand hover:text-white active:scale-90"
              @click="toggleMenu(docItem.id, $event)"
            >
              <PhDotsThreeVertical class="h-4 w-4" />
            </button>
            <!-- 操作菜单（设计稿 tb-menu 形态）：Teleport 到 body + fixed 定位避开表格容器裁切
                 （几何契约：末行菜单完整可见断言依赖此挂载方式，禁止迁回行内弹层） -->
            <Teleport to="body">
              <div
                v-if="openMenu && openMenu.id === docItem.id"
                data-testid="doc-menu"
                role="menu"
                class="fixed z-30 w-44 animate-menu-in rounded-xl border border-border bg-surface p-1.5 shadow-lg"
                :style="{ left: `${openMenu.x - 176}px`, top: `${openMenu.y + 6}px` }"
              >
                <DropdownMenuItem
                  data-testid="menu-view"
                  label="查看分片"
                  @click="viewChunks(docItem)"
                >
                  <template #icon>
                    <PhMagnifyingGlass class="h-4 w-4" aria-hidden="true" />
                  </template>
                </DropdownMenuItem>
                <DropdownMenuItem
                  data-testid="menu-reparse"
                  label="重新解析"
                  @click="handleReparse(docItem)"
                >
                  <template #icon>
                    <PhRepeat class="h-4 w-4" aria-hidden="true" />
                  </template>
                </DropdownMenuItem>
                <DropdownMenuItem
                  data-testid="menu-download"
                  label="下载"
                  @click="handleDownload(docItem)"
                >
                  <template #icon>
                    <PhDownloadSimple class="h-4 w-4" aria-hidden="true" />
                  </template>
                </DropdownMenuItem>
                <DropdownMenuItem
                  data-testid="menu-rename"
                  label="改标题"
                  @click="openRename(docItem)"
                >
                  <template #icon>
                    <PhPencilSimple class="h-4 w-4" aria-hidden="true" />
                  </template>
                </DropdownMenuItem>
                <DropdownMenuItem
                  data-testid="menu-delete"
                  label="删除"
                  tone="danger"
                  @click="requestDelete(docItem)"
                >
                  <template #icon>
                    <PhTrash class="h-4 w-4" aria-hidden="true" />
                  </template>
                </DropdownMenuItem>
              </div>
            </Teleport>
          </td>
        </tr>
      </DataTable>
    </div>

    <!-- 分页器：左「共 N 条」右 上/下页 + 页码（设计 §2.6） -->
    <div class="mt-4 flex items-center justify-between text-sm text-text-muted">
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

  <!-- 菜单点击外遮罩：点击任意处收起菜单 -->
  <div v-if="openMenu" class="fixed inset-0 z-20" @click="closeMenu" />

  <!-- 上传 Dialog（设计稿弹窗形态）：kbId 必选 + courseId 可选 + 拖拽区 + 进度条 -->
  <div
    v-if="uploadOpen"
    data-testid="upload-dialog"
    class="fixed inset-0 z-50 flex animate-fade-in items-center justify-center bg-overlay p-4"
    @keydown.esc="closeUpload"
    @click.self="closeUpload"
  >
    <div
      class="max-h-[85vh] w-full max-w-[520px] overflow-y-auto rounded-2xl bg-surface p-6 shadow-lg"
      role="dialog"
      aria-modal="true"
      @click.stop
    >
      <h2 class="text-base font-semibold text-text">上传文档</h2>
      <form
        data-testid="upload-form"
        class="mt-5 space-y-4"
        novalidate
        @submit.prevent="submitUpload"
      >
        <div>
          <span class="mb-1.5 block text-sm font-medium text-text">
            所属知识库 <span class="text-danger">*</span>
          </span>
          <!-- 知识库选择：remote-select 单选（防抖 300ms + 取消，契约 E） -->
          <RemoteSelect
            :model-value="uploadKb"
            :get-value="(k: KnowledgeBaseVO) => k.id"
            :get-label="(k: KnowledgeBaseVO) => k.name"
            :fetcher="fetchKbs"
            placeholder="搜索知识库名称"
            empty-text="没有匹配的知识库"
            data-testid="upload-kb"
            @update:model-value="onUploadKbSelect"
          />
        </div>
        <div>
          <label for="upload-title" class="mb-1.5 block text-sm font-medium text-text">
            标题 <span class="text-danger">*</span>
          </label>
          <input
            id="upload-title"
            v-model="uploadTitle"
            data-testid="upload-title"
            type="text"
            aria-label="文档标题"
            placeholder="请输入文档标题"
            class="h-10 w-full rounded-xl border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
          />
        </div>
        <div>
          <span class="mb-1.5 block text-sm font-medium text-text"> 关联课程（可选） </span>
          <!-- 课程选择：remote-select 单选（防抖 300ms + 取消，不选则归属通用资料） -->
          <RemoteSelect
            :model-value="uploadCourse"
            :get-value="(c: CourseDTO) => c.id"
            :get-label="(c: CourseDTO) => c.title"
            :fetcher="fetchCourses"
            placeholder="输入课程名搜索（不选则归属通用资料）"
            empty-text="没有匹配的课程"
            data-testid="upload-course"
            @update:model-value="onUploadCourseSelect"
          />
        </div>
        <div>
          <!-- 拖拽区：dragover/drop 拦截浏览器默认行为，点击唤起文件选择 -->
          <div
            data-testid="drop-zone"
            role="button"
            tabindex="0"
            aria-label="选择上传文件"
            class="flex cursor-pointer flex-col items-center justify-center rounded-xl border-2 border-dashed border-border bg-brand-light px-4 py-8 text-center transition-colors duration-150 hover:border-brand/50"
            @dragover.prevent
            @drop.prevent="onDrop"
            @click="openFilePicker"
          >
            <span class="grid h-12 w-12 place-items-center rounded-full bg-surface shadow-xs">
              <PhUploadSimple class="h-6 w-6 text-brand" />
            </span>
            <p class="mt-3 text-sm font-medium text-text">
              {{ uploadFile?.name ?? '拖拽文件到此处，或点击选择' }}
            </p>
            <!-- 白名单文案由常量派生（BUG-30 修复）：后续白名单扩展示自动同步，不再漂移 -->
            <p class="mt-1 text-xs text-text-subtle">
              支持 {{ UPLOAD_FILE_TYPES.join('/') }}，≤{{ UPLOAD_MAX_SIZE_MB }}MB
            </p>
            <input
              ref="fileInputRef"
              type="file"
              data-testid="file-input"
              class="hidden"
              :accept="UPLOAD_FILE_TYPES.map((t) => `.${t}`).join(',')"
              @change="onFileChange"
            />
          </div>
        </div>
        <!-- XHR 上传进度条（设计稿渐变进度形态）：onUploadProgress 回调驱动宽度（设计 §2.4.2） -->
        <div v-if="uploading" data-testid="upload-progress-wrap">
          <div class="h-1.5 w-full overflow-hidden rounded-full bg-brand-light">
            <div
              data-testid="upload-progress"
              class="h-full rounded-full bg-gradient-to-r from-brand to-brand-strong transition-[width] duration-150"
              :style="{ width: `${progress}%` }"
            />
          </div>
          <p class="mt-1 text-xs tabular-nums text-text-muted">{{ progress }}%</p>
        </div>
        <p v-if="uploadError" data-testid="upload-error" class="text-xs text-danger">
          {{ uploadError }}
        </p>
        <div class="flex justify-end gap-2 pt-2">
          <Button variant="outline" :disabled="uploading" @click="closeUpload">取消</Button>
          <Button type="submit" data-testid="submit-upload" :disabled="uploading">
            <PhSpinnerGap v-if="uploading" class="h-4 w-4 animate-spin" />
            {{ uploading ? `上传中 ${progress}%` : '上传' }}
          </Button>
        </div>
      </form>
    </div>
  </div>

  <!-- 改标题 Dialog -->
  <div
    v-if="renameTarget"
    data-testid="rename-dialog"
    class="fixed inset-0 z-50 flex animate-fade-in items-center justify-center bg-overlay p-4"
    @keydown.esc="closeRename"
    @click.self="closeRename"
  >
    <div
      class="w-full max-w-[440px] animate-menu-in rounded-2xl bg-surface p-6 shadow-lg"
      role="dialog"
      aria-modal="true"
      @click.stop
    >
      <h2 class="text-base font-semibold text-text">改标题</h2>
      <div class="mt-5">
        <label for="rename-input" class="mb-1.5 block text-sm font-medium text-text">
          标题 <span class="text-danger">*</span>
        </label>
        <input
          id="rename-input"
          v-model="renameTitle"
          data-testid="rename-input"
          type="text"
          aria-label="新标题"
          class="h-10 w-full rounded-xl border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 focus:border-brand focus:ring-2 focus:ring-brand/20"
        />
        <p v-if="renameError" class="mt-1 text-xs text-danger">{{ renameError }}</p>
      </div>
      <div class="mt-5 flex justify-end gap-2">
        <Button variant="outline" @click="closeRename">取消</Button>
        <Button data-testid="submit-rename" :disabled="renameSubmitting" @click="submitRename">
          <PhSpinnerGap v-if="renameSubmitting" class="h-4 w-4 animate-spin" />
          {{ renameSubmitting ? '保存中' : '保存' }}
        </Button>
      </div>
    </div>
  </div>

  <!-- 单条删除二次确认（danger 实底 + 不可恢复告警，设计 §2.6） -->
  <div
    v-if="deletingDoc"
    data-testid="delete-dialog"
    class="fixed inset-0 z-50 flex animate-fade-in items-center justify-center bg-overlay p-4"
    @keydown.esc="cancelDelete"
    @click.self="cancelDelete"
  >
    <div
      class="w-full max-w-[440px] animate-menu-in rounded-2xl bg-surface p-6 shadow-lg"
      role="alertdialog"
      aria-modal="true"
      @click.stop
    >
      <div class="flex items-start gap-3">
        <div class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-red-50">
          <PhWarningCircle class="h-5 w-5 text-danger" />
        </div>
        <div>
          <h2 class="text-base font-semibold text-text">删除文档</h2>
          <p class="mt-2 text-sm leading-relaxed text-text-muted">
            删除后该文档的全部分片将被一并移除，且不可恢复。确认删除「{{ deletingDoc.title }}」？
          </p>
        </div>
      </div>
      <div class="mt-5 flex justify-end gap-2">
        <Button variant="outline" data-testid="cancel-delete" @click="cancelDelete">取消</Button>
        <Button
          variant="danger"
          data-testid="confirm-delete"
          :disabled="deletingLoading"
          @click="confirmDelete"
        >
          <PhSpinnerGap v-if="deletingLoading" class="h-4 w-4 animate-spin" />
          {{ deletingLoading ? '删除中' : '确认删除' }}
        </Button>
      </div>
    </div>
  </div>

  <!-- 批量删除二次确认（allSettled 循环单条，聚合 toast） -->
  <div
    v-if="batchConfirmOpen"
    data-testid="batch-dialog"
    class="fixed inset-0 z-50 flex animate-fade-in items-center justify-center bg-overlay p-4"
    @keydown.esc="batchConfirmOpen = false"
    @click.self="batchConfirmOpen = false"
  >
    <div
      class="w-full max-w-[440px] animate-menu-in rounded-2xl bg-surface p-6 shadow-lg"
      role="alertdialog"
      aria-modal="true"
      @click.stop
    >
      <div class="flex items-start gap-3">
        <div class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-red-50">
          <PhWarningCircle class="h-5 w-5 text-danger" />
        </div>
        <div>
          <h2 class="text-base font-semibold text-text">批量删除</h2>
          <p class="mt-2 text-sm leading-relaxed text-text-muted">
            将逐条删除已勾选的
            {{ selected.size }} 个文档（含分片），失败项保留在列表内可单独重试。 确认删除？
          </p>
        </div>
      </div>
      <div class="mt-5 flex justify-end gap-2">
        <Button variant="outline" data-testid="cancel-batch" @click="batchConfirmOpen = false">
          取消
        </Button>
        <Button
          variant="danger"
          data-testid="confirm-batch"
          :disabled="batchDeleting"
          @click="confirmBatchDelete"
        >
          <PhSpinnerGap v-if="batchDeleting" class="h-4 w-4 animate-spin" />
          {{ batchDeleting ? '删除中' : '确认删除' }}
        </Button>
      </div>
    </div>
  </div>
</template>

<style scoped>
/**
 * 勾选列窄列修正：DataTable 首列默认 22px 左内距面向文本首列（设计稿），
 * 本表首列为勾选框，收窄为 8px 保持复选框视觉居中。
 */
thead tr th:first-child,
tbody tr td:first-child {
  padding-right: 8px;
  padding-left: 8px;
}
</style>
