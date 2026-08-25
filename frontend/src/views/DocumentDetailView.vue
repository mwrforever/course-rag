<script setup lang="ts">
/**
 * 文档详情页（设计 §2.4.2 详情行 + §2.5 状态可视化 + G14 静态状态机示意）
 *
 * 能力清单：
 * 1. 信息卡：标题 / 类型 Badge / 大小（formatFileSize）/ 分片数 / 上传·更新时间 /
 *    状态 Badge（8 态共享组件）+ FAILED 错误信息（mono 固定展示）
 * 2. 分片列表：headingPath + 内容 2 行截断（line-clamp-2）+ 分页（chunks?docId=）
 * 3. 状态时间线：PENDING→INDEXED 七步静态状态机示意，当前态 brand 高亮 /
 *    前序 emerald 完成 / 后续中性待处理；FAILED 呈现红色终态分支（错误详情 + 重新解析）
 * 4. 四态：loading 骨架 / empty（暂无分片）/ error 横幅重试 / 正常
 *
 * 契约要点：id/total 为 Long 字符串铁律；page/size 为 number；时间 ISO-8601。
 * 数据源：文档详情 + 分片列表两个独立请求（分片列表自带分页与独立错误重试）。
 *
 * 线程安全注意：全部状态为组件私有 ref，无跨实例共享可变状态。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { PhArrowLeft, PhCheck, PhSpinnerGap, PhWarningCircle, PhX } from '@phosphor-icons/vue'

import EtlStatusBadge from '@/components/EtlStatusBadge.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ApiError, chunkApi, documentApi } from '@/lib/api'
import { showToast } from '@/lib/toast'
import { formatDateTime, formatFileSize } from '@/lib/utils'

import type { DocumentChunkVO, DocumentParseStatus, DocumentVO } from '@/lib/types'

/** 分片每页条数（设计 §2.6 分页器） */
const CHUNK_PAGE_SIZE = 10

/** 状态机七步（设计 §2.5 八态体系去掉 FAILED 的分支，G14 静态示意主干） */
const STEP_ORDER: DocumentParseStatus[] = [
  'PENDING',
  'PARSING',
  'PARSED',
  'CHUNKING',
  'CHUNKED',
  'EMBEDDING',
  'INDEXED',
]

/** 步骤中文标签（与文档列表 STATUS_OPTIONS 文案一致） */
const STEP_LABEL: Record<DocumentParseStatus, string> = {
  PENDING: '排队中',
  PARSING: '解析中',
  PARSED: '解析完成',
  CHUNKING: '分片中',
  CHUNKED: '分片完成',
  EMBEDDING: '向量化中',
  INDEXED: '已入库',
  FAILED: '失败',
}

const route = useRoute()
/** 路由参数文档 id（Long 序列化字符串，铁律 string 处理） */
const docId = computed(() => String(route.params.id))

/**
 * 接口错误分级文案（与文档列表 messageOf 同构）
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
// 文档详情（信息卡 + 状态时间线数据源）
// ====================================================================

const doc = ref<DocumentVO | null>(null)
const docLoading = ref(true)
const docError = ref('')

/** 当前解析状态（doc 未加载完成时按 PENDING 兜底，时间线随详情加载后渲染） */
const parseStatus = computed(() => doc.value?.parseStatus ?? 'PENDING')

/** 拉取文档详情：成功回写 doc，失败进入文档级错误态（四态之一） */
async function loadDoc() {
  docLoading.value = true
  docError.value = ''
  try {
    doc.value = await documentApi.get(docId.value)
  } catch (err) {
    docError.value = messageOf(err, '文档加载失败，请稍后重试')
  } finally {
    docLoading.value = false
  }
}

// ====================================================================
// 分片列表（独立请求 + 独立分页 + 独立错误重试）
// ====================================================================

const chunks = ref<DocumentChunkVO[]>([])
const chunkLoading = ref(true)
const chunkError = ref('')
const chunkPage = ref(1)
const chunkTotal = ref('0')

const chunkTotalPages = computed(() =>
  Math.max(1, Math.ceil(Number(chunkTotal.value) / CHUNK_PAGE_SIZE)),
)

/**
 * 拉取当前页分片（docId 固定，分页参数 page/size；total 回写分页器）
 *
 * 边界：分页参数由 chunkApi.list 传递，翻页越界由 changeChunkPage 拦截。
 */
async function loadChunks() {
  chunkLoading.value = true
  chunkError.value = ''
  try {
    const res = await chunkApi.list({
      docId: docId.value,
      page: chunkPage.value,
      size: CHUNK_PAGE_SIZE,
    })
    chunks.value = res.records ?? []
    chunkTotal.value = res.total
  } catch (err) {
    chunkError.value = messageOf(err, '分片加载失败，请稍后重试')
  } finally {
    chunkLoading.value = false
  }
}

/** 翻页：越界保护（首页/末页禁用态由 disabled 兜底） */
function changeChunkPage(next: number) {
  if (next < 1 || next > chunkTotalPages.value) return
  chunkPage.value = next
  loadChunks()
}

onMounted(() => {
  loadDoc()
  loadChunks()
})

// ====================================================================
// 状态时间线（G14 静态状态机示意）
// ====================================================================

/**
 * 步骤状态推导：当前态 brand 高亮，前序 emerald 完成，后续中性待处理；
 * INDEXED 作为终态整体标记为完成（设计 §2.5 终态收敛）。
 *
 * @param step 状态机中的一步
 * @returns done（已完成）/ current（当前高亮）/ pending（待处理）
 */
function stepState(step: DocumentParseStatus): 'done' | 'current' | 'pending' {
  const currentIdx = STEP_ORDER.indexOf(parseStatus.value)
  const stepIdx = STEP_ORDER.indexOf(step)
  if (stepIdx < currentIdx) return 'done'
  if (stepIdx === currentIdx) return parseStatus.value === 'INDEXED' ? 'done' : 'current'
  return 'pending'
}

// ====================================================================
// 重新解析（FAILED 终态恢复入口，列表行与详情页共用同一语义）
// ====================================================================

const reparseLoading = ref(false)

/** 重新解析：POST reparse → toast → 详情与分片一并刷新（状态可能流转） */
async function handleReparse() {
  if (!doc.value) return
  reparseLoading.value = true
  try {
    await documentApi.reparse(doc.value.id)
    showToast('已重新解析，稍后查看最新状态', 'success')
    await Promise.all([loadDoc(), loadChunks()])
  } catch (err) {
    showToast(messageOf(err, '重新解析失败，请稍后重试'), 'danger')
  } finally {
    reparseLoading.value = false
  }
}
</script>

<template>
  <!-- 返回文档列表链接（面包屑语义，设计 §2.3 页头） -->
  <router-link
    to="/knowledge/documents"
    class="mb-4 inline-flex items-center gap-1.5 text-sm text-text-muted transition-colors duration-150 hover:text-brand"
  >
    <PhArrowLeft class="h-4 w-4" />
    返回文档列表
  </router-link>

  <!-- 加载态：信息卡 + 状态条 + 分片区骨架（与最终布局同形，设计 §1.7） -->
  <div
    v-if="docLoading"
    data-testid="detail-skeleton"
    aria-label="文档详情加载中"
    class="space-y-4"
  >
    <div class="animate-pulse rounded-xl border border-border bg-surface p-5">
      <div class="h-5 w-56 rounded bg-slate-200" />
      <div class="mt-4 grid grid-cols-2 gap-4 md:grid-cols-4">
        <div v-for="i in 4" :key="`meta-${i}`" class="h-4 w-24 rounded bg-slate-200" />
      </div>
    </div>
    <div class="animate-pulse rounded-xl border border-border bg-surface p-5">
      <div class="h-4 w-24 rounded bg-slate-200" />
      <div class="mt-4 flex gap-2">
        <div v-for="i in 7" :key="`step-${i}`" class="h-10 w-20 rounded bg-slate-200" />
      </div>
    </div>
    <div class="animate-pulse rounded-xl border border-border bg-surface p-5">
      <div class="h-4 w-28 rounded bg-slate-200" />
      <div v-for="i in 4" :key="`chunk-${i}`" class="mt-3 h-9 rounded bg-slate-50" />
    </div>
  </div>

  <!-- 文档级错误态：页内横幅 + 重试（设计 §1.7） -->
  <div
    v-else-if="docError"
    data-testid="detail-error"
    role="alert"
    class="flex items-center justify-between gap-4 rounded-lg border border-danger/30 bg-red-50 px-4 py-3"
  >
    <span class="text-sm text-danger">{{ docError }}</span>
    <Button variant="outline" size="sm" data-testid="retry-detail" @click="loadDoc">重试</Button>
  </div>

  <!-- 正常态：信息卡 + 状态时间线 + 分片列表 -->
  <template v-else>
    <!-- 信息卡（设计 §2.4.2：标题/类型/大小/分片数/时间/状态） -->
    <section
      data-testid="doc-info"
      class="rounded-xl border border-border bg-surface p-5"
      aria-label="文档信息"
    >
      <div class="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 class="text-base font-semibold text-text">{{ doc?.title }}</h2>
          <p class="mt-1 text-xs text-text-muted">文档 ID：{{ doc?.id }}</p>
        </div>
        <!-- 状态 Badge：共享 8 态组件（设计 §2.5 全局唯一映射） -->
        <div data-testid="detail-status">
          <EtlStatusBadge
            :status="doc?.parseStatus ?? 'PENDING'"
            :error-message="doc?.errorMessage"
          />
        </div>
      </div>
      <dl class="mt-4 grid grid-cols-2 gap-4 md:grid-cols-4">
        <div>
          <dt class="text-xs text-text-subtle">类型</dt>
          <dd class="mt-1">
            <Badge variant="default">{{ doc?.fileType.toUpperCase() }}</Badge>
          </dd>
        </div>
        <div>
          <dt class="text-xs text-text-subtle">大小</dt>
          <dd class="mt-1 tabular-nums text-sm text-text">
            {{ formatFileSize(doc?.fileSize ?? '0') }}
          </dd>
        </div>
        <div>
          <dt class="text-xs text-text-subtle">分片数</dt>
          <dd class="mt-1 tabular-nums text-sm text-text">{{ doc?.chunkCount }}</dd>
        </div>
        <div>
          <dt class="text-xs text-text-subtle">更新时间</dt>
          <dd class="mt-1 tabular-nums text-sm text-text">
            {{ formatDateTime(doc?.updatedAt ?? '') }}
          </dd>
        </div>
        <div>
          <dt class="text-xs text-text-subtle">上传时间</dt>
          <dd class="mt-1 tabular-nums text-sm text-text">
            {{ formatDateTime(doc?.createdAt ?? '') }}
          </dd>
        </div>
      </dl>
      <!-- FAILED 错误信息固定展示（mono 13px，与设计 §2.5 错误展开区同规格） -->
      <p
        v-if="doc?.parseStatus === 'FAILED' && doc.errorMessage"
        class="mt-4 break-all rounded-md border border-danger/30 bg-red-50 px-3 py-2 font-mono text-[13px] leading-relaxed text-danger"
      >
        {{ doc.errorMessage }}
      </p>
    </section>

    <!-- 状态时间线（G14 静态状态机示意：七步 + FAILED 分支） -->
    <section
      data-testid="status-timeline"
      class="mt-4 rounded-xl border border-border bg-surface p-5"
      aria-label="解析状态时间线"
    >
      <h3 class="text-sm font-semibold text-text">解析状态</h3>
      <ol v-if="parseStatus !== 'FAILED'" class="mt-4 flex flex-wrap items-center gap-x-2 gap-y-3">
        <template v-for="(step, i) in STEP_ORDER" :key="step">
          <li
            :data-testid="`timeline-${step}`"
            :class="['flex w-24 flex-col items-center gap-1.5 text-center', stepState(step)]"
          >
            <!-- 节点：已完成 emerald 勾 / 当前 brand 高亮（工作态 spinner）/ 待处理中性圆 -->
            <span
              :class="[
                'flex h-8 w-8 items-center justify-center rounded-full border-2',
                stepState(step) === 'done' && 'border-emerald-500 bg-emerald-50 text-emerald-600',
                stepState(step) === 'current' && 'border-brand bg-brand-soft text-brand-strong',
                stepState(step) === 'pending' && 'border-slate-200 bg-surface text-slate-400',
              ]"
            >
              <PhCheck v-if="stepState(step) === 'done'" class="h-4 w-4" />
              <PhSpinnerGap
                v-else-if="stepState(step) === 'current' && parseStatus === 'CHUNKING'"
                class="h-4 w-4 animate-spin"
              />
              <span v-else-if="stepState(step) === 'current'">{{ i + 1 }}</span>
            </span>
            <span
              :class="[
                'text-[11px] font-medium',
                stepState(step) === 'pending' ? 'text-text-subtle' : 'text-text',
              ]"
            >
              {{ STEP_LABEL[step] }}
            </span>
          </li>
          <span
            v-if="i < STEP_ORDER.length - 1"
            aria-hidden="true"
            :class="[
              'h-px w-6',
              stepState(STEP_ORDER[i + 1]) === 'pending' ? 'bg-slate-200' : 'bg-emerald-400',
            ]"
          />
        </template>
      </ol>
      <!-- FAILED 终态分支：红色高亮 + 错误详情 + 重新解析（设计 §2.5） -->
      <div
        v-else
        data-testid="timeline-failed"
        class="failed mt-4 rounded-lg border border-danger/30 bg-red-50 p-4"
      >
        <div class="flex items-center gap-2 text-danger">
          <PhX class="h-4 w-4" />
          <span class="text-sm font-semibold">解析失败</span>
        </div>
        <p class="mt-2 break-all font-mono text-[13px] leading-relaxed text-danger">
          {{ doc?.errorMessage || '未返回错误详情' }}
        </p>
        <Button
          variant="danger"
          size="sm"
          data-testid="detail-reparse"
          class="mt-3"
          :disabled="reparseLoading"
          @click="handleReparse"
        >
          <PhSpinnerGap v-if="reparseLoading" class="h-4 w-4 animate-spin" />
          {{ reparseLoading ? '解析中' : '重新解析' }}
        </Button>
      </div>
    </section>

    <!-- 分片列表区：独立四态（加载/空/错误/正常）+ 分页 -->
    <section class="mt-4">
      <h3 class="mb-3 text-base font-semibold text-text">文档分片</h3>

      <!-- 分片加载骨架：4 行灰条（与列表同形） -->
      <div
        v-if="chunkLoading"
        data-testid="chunk-skeleton"
        class="overflow-hidden rounded-xl border border-border bg-surface"
        aria-label="分片列表加载中"
      >
        <div
          v-for="i in 4"
          :key="`chunk-row-${i}`"
          class="h-11 animate-pulse border-b border-border bg-slate-50"
        />
      </div>

      <!-- 分片错误态：横幅 + 重试（与文档级错误独立） -->
      <div
        v-else-if="chunkError"
        data-testid="chunk-error"
        role="alert"
        class="flex items-center justify-between gap-4 rounded-lg border border-danger/30 bg-red-50 px-4 py-3"
      >
        <span class="text-sm text-danger">{{ chunkError }}</span>
        <Button variant="outline" size="sm" data-testid="retry-chunks" @click="loadChunks">
          重试
        </Button>
      </div>

      <!-- 分片空态：一句话（禁裸「暂无数据」） -->
      <div
        v-else-if="chunks.length === 0"
        class="flex flex-col items-center justify-center rounded-xl border border-dashed border-border bg-surface py-12 text-center"
      >
        <PhWarningCircle class="h-8 w-8 text-text-subtle" />
        <p class="mt-3 text-sm font-medium text-text">暂无分片</p>
        <p class="mt-1 text-xs text-text-muted">文档解析入库后可在此查看分片内容</p>
      </div>

      <!-- 正常态：分片列表（headingPath + 内容 2 行截断）+ 分页器 -->
      <template v-else>
        <ul
          data-testid="chunk-list"
          class="divide-y divide-border overflow-hidden rounded-xl border border-border bg-surface"
        >
          <li
            v-for="c in chunks"
            :key="c.id"
            class="px-4 py-3 transition-colors duration-150 hover:bg-surface-2"
          >
            <div class="flex items-center justify-between gap-3">
              <span class="truncate text-xs font-medium text-text-muted">
                {{ c.headingPath || `第 ${c.chunkIndex} 片` }}
              </span>
              <span class="shrink-0 tabular-nums text-xs text-text-subtle">
                #{{ c.chunkIndex }} · {{ c.startPage }}-{{ c.endPage }} 页
              </span>
            </div>
            <!-- 内容 2 行截断（设计 §2.4.3 分片预览同规格） -->
            <p
              :data-testid="`chunk-content-${c.id}`"
              class="mt-1 line-clamp-2 text-sm leading-relaxed text-text"
            >
              {{ c.content }}
            </p>
          </li>
        </ul>

        <!-- 分片分页器：左「共 N 条」右 上/下页 + 页码（设计 §2.6） -->
        <div class="mt-4 flex items-center justify-between text-sm text-text-muted">
          <span>
            共 <span class="tabular-nums text-text">{{ chunkTotal }}</span> 条
          </span>
          <div class="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              data-testid="chunk-prev"
              :disabled="chunkPage <= 1"
              @click="changeChunkPage(chunkPage - 1)"
            >
              上一页
            </Button>
            <span class="tabular-nums">第 {{ chunkPage }} / {{ chunkTotalPages }} 页</span>
            <Button
              variant="outline"
              size="sm"
              data-testid="chunk-next"
              :disabled="chunkPage >= chunkTotalPages"
              @click="changeChunkPage(chunkPage + 1)"
            >
              下一页
            </Button>
          </div>
        </div>
      </template>
    </section>
  </template>
</template>
