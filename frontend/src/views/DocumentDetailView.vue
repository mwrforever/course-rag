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
 * N6a 视觉重制（2026-08-27 紫系换肤）：页头迁 PageHead（文档标题为 h1、
 * 状态徽章入动作区）、分片列表迁 DataTable（lav 表头/悬停行/行级联入场）、
 * 空态迁 EmptyState、各区卡片 v-reveal 级联入场；查询/时间线/重新解析逻辑零改动。
 *
 * 线程安全注意：全部状态为组件私有 ref，无跨实例共享可变状态。
 */
import { computed, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { useRoute } from 'vue-router'
import { PhArrowLeft, PhCheck, PhSpinnerGap, PhWarningCircle, PhX } from '@phosphor-icons/vue'

import EtlStatusBadge from '@/components/EtlStatusBadge.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { DataTable } from '@/components/ui/data-table'
import { EmptyState } from '@/components/ui/empty-state'
import { PageHead } from '@/components/ui/page-head'
import { ApiError, chunkApi, documentApi } from '@/lib/api'
import { showToast } from '@/lib/toast'
import { formatDateTime, formatFileSize } from '@/lib/utils'

import type { DocumentParseStatus } from '@/lib/types'

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
// 文档详情（信息卡 + 状态时间线数据源；查询键含路由 docId，参数变化自动重拉）
// ====================================================================

const {
  data: doc,
  isLoading: docLoading,
  isError: docIsError,
  error: docQueryError,
  refetch: refetchDoc,
} = useQuery({
  queryKey: computed(() => ['admin-document-detail', docId.value]),
  queryFn: () => documentApi.get(docId.value),
})

/** 当前解析状态（doc 未加载完成时按 PENDING 兜底，时间线随详情加载后渲染） */
const parseStatus = computed(() => doc.value?.parseStatus ?? 'PENDING')

/** 文档级加载失败横幅文案（queryError 非空时透出；503 统一降级） */
const docErrorText = computed(() =>
  docIsError.value ? messageOf(docQueryError.value, '文档加载失败，请稍后重试') : '',
)

// ====================================================================
// 分片列表（独立请求 + 独立分页 + 独立错误重试）
// ====================================================================

const chunkPage = ref(1)

const {
  data: chunkData,
  isLoading: chunkLoading,
  isError: chunkIsError,
  error: chunkQueryError,
  refetch: refetchChunks,
} = useQuery({
  queryKey: computed(() => ['admin-document-chunks', docId.value, chunkPage.value]),
  queryFn: () =>
    chunkApi.list({ docId: docId.value, page: chunkPage.value, size: CHUNK_PAGE_SIZE }),
})

/** 分片行数据：total 为 Long 字符串铁律 */
const chunks = computed(() => chunkData.value?.records ?? [])
const chunkTotal = computed(() => chunkData.value?.total ?? '0')

const chunkTotalPages = computed(() =>
  Math.max(1, Math.ceil(Number(chunkTotal.value) / CHUNK_PAGE_SIZE)),
)

/** 分片级加载失败横幅文案（queryError 非空时透出；503 统一降级） */
const chunkErrorText = computed(() =>
  chunkIsError.value ? messageOf(chunkQueryError.value, '分片加载失败，请稍后重试') : '',
)

/** 翻页：越界保护（首页/末页禁用态由 disabled 兜底），页码变化自动重拉 */
function changeChunkPage(next: number) {
  if (next < 1 || next > chunkTotalPages.value) return
  chunkPage.value = next
}

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

const queryClient = useQueryClient()

/** 重新解析提交（状态可能流转；成功后详情与分片一并失效重拉） */
const { isPending: reparseLoading, mutate: handleReparse } = useMutation({
  mutationFn: () => documentApi.reparse(doc.value!.id),
  onSuccess: () => {
    showToast('已重新解析，稍后查看最新状态', 'success')
    // 失效匹配为逐项等值比较（非字符串前缀），详情与分片两个键分别失效
    queryClient.invalidateQueries({ queryKey: ['admin-document-detail'] })
    queryClient.invalidateQueries({ queryKey: ['admin-document-chunks'] })
  },
  onError: (err) => {
    showToast(messageOf(err, '重新解析失败，请稍后重试'), 'danger')
  },
})
</script>

<template>
  <!-- 返回文档列表链接（面包屑语义，设计 §2.3 页头） -->
  <router-link
    to="/knowledge/documents"
    class="mb-4 inline-flex items-center gap-1.5 text-sm text-text-muted transition-colors duration-150 hover:text-brand"
  >
    <PhArrowLeft class="h-4 w-4" aria-hidden="true" />
    返回文档列表
  </router-link>

  <!-- 加载态：信息卡 + 状态条 + 分片区骨架（与最终布局同形，设计 §1.7） -->
  <div
    v-if="docLoading"
    data-testid="detail-skeleton"
    aria-label="文档详情加载中"
    class="space-y-4"
  >
    <div class="animate-pulse rounded-2xl border border-border bg-surface px-6 py-5">
      <div class="h-5 w-56 rounded bg-slate-200" />
      <div class="mt-4 grid grid-cols-2 gap-4 md:grid-cols-4">
        <div v-for="i in 4" :key="`meta-${i}`" class="h-4 w-24 rounded bg-slate-200" />
      </div>
    </div>
    <div class="animate-pulse rounded-2xl border border-border bg-surface px-6 py-5">
      <div class="h-4 w-24 rounded bg-slate-200" />
      <div class="mt-4 flex gap-2">
        <div v-for="i in 7" :key="`step-${i}`" class="h-10 w-20 rounded bg-slate-200" />
      </div>
    </div>
    <div class="animate-pulse rounded-2xl border border-border bg-surface px-6 py-5">
      <div class="h-4 w-28 rounded bg-slate-200" />
      <div v-for="i in 4" :key="`chunk-${i}`" class="mt-3 h-9 rounded bg-slate-50" />
    </div>
  </div>

  <!-- 文档级错误态：页内横幅 + 重试（设计 §1.7） -->
  <div
    v-else-if="docErrorText"
    data-testid="detail-error"
    role="alert"
    class="flex items-center justify-between gap-4 rounded-xl border border-danger/30 bg-red-50 px-4 py-3"
  >
    <span class="text-sm text-danger">{{ docErrorText }}</span>
    <Button variant="outline" size="sm" data-testid="retry-detail" @click="refetchDoc">重试</Button>
  </div>

  <!-- 正常态：页头（标题/ID/状态）+ 信息卡 + 状态时间线 + 分片列表 -->
  <template v-else>
    <!-- 页头：文档标题为 h1（22px/800）+ 文档 ID 副题 + 状态徽章动作区，v-reveal 入场 -->
    <PageHead v-reveal :title="doc?.title ?? '文档详情'" :subtitle="`文档 ID：${doc?.id}`">
      <template #actions>
        <!-- 状态 Badge：共享 8 态组件（设计 §2.5 全局唯一映射） -->
        <div data-testid="detail-status">
          <EtlStatusBadge
            :status="doc?.parseStatus ?? 'PENDING'"
            :error-message="doc?.errorMessage"
          />
        </div>
      </template>
    </PageHead>

    <!-- 信息卡（设计 §2.4.2：类型/大小/分片数/上传·更新时间；16px 圆角紫调柔影卡片） -->
    <section
      v-reveal="80"
      data-testid="doc-info"
      class="mt-5 rounded-2xl border border-border bg-surface px-6 py-5 shadow-xs"
      aria-label="文档信息"
    >
      <dl class="grid grid-cols-2 gap-4 md:grid-cols-5">
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
          <dt class="text-xs text-text-subtle">上传时间</dt>
          <dd class="mt-1 tabular-nums text-sm text-text">
            {{ formatDateTime(doc?.createdAt ?? '') }}
          </dd>
        </div>
        <div>
          <dt class="text-xs text-text-subtle">更新时间</dt>
          <dd class="mt-1 tabular-nums text-sm text-text">
            {{ formatDateTime(doc?.updatedAt ?? '') }}
          </dd>
        </div>
      </dl>
      <!-- FAILED 错误信息固定展示（mono 13px，与设计 §2.5 错误展开区同规格） -->
      <p
        v-if="doc?.parseStatus === 'FAILED' && doc.errorMessage"
        class="mt-4 break-all rounded-lg border border-danger/30 bg-red-50 px-3 py-2 font-mono text-[13px] leading-relaxed text-danger"
      >
        {{ doc.errorMessage }}
      </p>
    </section>

    <!-- 状态时间线（G14 静态状态机示意：七步 + FAILED 分支） -->
    <section
      v-reveal="160"
      data-testid="status-timeline"
      class="mt-4 rounded-2xl border border-border bg-surface px-6 py-5 shadow-xs"
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
              <PhCheck v-if="stepState(step) === 'done'" class="h-4 w-4" aria-hidden="true" />
              <PhSpinnerGap
                v-else-if="stepState(step) === 'current' && parseStatus === 'CHUNKING'"
                class="h-4 w-4 animate-spin"
                aria-hidden="true"
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
        class="failed mt-4 rounded-xl border border-danger/30 bg-red-50 p-4"
      >
        <div class="flex items-center gap-2 text-danger">
          <PhX class="h-4 w-4" aria-hidden="true" />
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
          <PhSpinnerGap v-if="reparseLoading" class="h-4 w-4 animate-spin" aria-hidden="true" />
          {{ reparseLoading ? '解析中' : '重新解析' }}
        </Button>
      </div>
    </section>

    <!-- 分片列表区：独立四态（加载/空/错误/正常）+ 分页 -->
    <section v-reveal="240" class="mt-4">
      <h3 class="mb-3 text-base font-semibold text-text">文档分片</h3>

      <!-- 分片加载骨架：4 行灰条（与列表同形） -->
      <div
        v-if="chunkLoading"
        data-testid="chunk-skeleton"
        class="overflow-hidden rounded-2xl border border-border bg-surface"
        aria-label="分片列表加载中"
      >
        <div
          v-for="i in 4"
          :key="`chunk-row-${i}`"
          class="h-[58px] animate-pulse border-b border-border bg-slate-50 last:border-b-0"
        />
      </div>

      <!-- 分片错误态：横幅 + 重试（与文档级错误独立） -->
      <div
        v-else-if="chunkErrorText"
        data-testid="chunk-error"
        role="alert"
        class="flex items-center justify-between gap-4 rounded-xl border border-danger/30 bg-red-50 px-4 py-3"
      >
        <span class="text-sm text-danger">{{ chunkErrorText }}</span>
        <Button variant="outline" size="sm" data-testid="retry-chunks" @click="refetchChunks">
          重试
        </Button>
      </div>

      <!-- 分片空态：EmptyState 统一形态（禁裸「暂无数据」） -->
      <div
        v-else-if="chunks.length === 0"
        class="rounded-2xl border border-dashed border-border bg-surface"
      >
        <EmptyState title="暂无分片" description="文档解析入库后可在此查看分片内容">
          <template #icon>
            <PhWarningCircle class="h-6 w-6" aria-hidden="true" />
          </template>
        </EmptyState>
      </div>

      <!-- 正常态：分片表格（DataTable：lav 表头/悬停行/行级联入场）+ 分页器 -->
      <template v-else>
        <div class="rounded-2xl border border-border bg-surface pb-2 shadow-xs">
          <DataTable data-testid="chunk-list" label="文档分片列表">
            <template #header>
              <tr>
                <th class="w-[26%]">分片位置</th>
                <th>内容</th>
                <th class="w-[12%]">页码</th>
              </tr>
            </template>
            <!-- 行数据经默认插槽进 tbody（DataTable 已渲染 thead/tbody 骨架） -->
            <tr v-for="c in chunks" :key="c.id">
              <!-- 位置列：headingPath 主文字色 + 序号（空 headingPath 回退「第 N 片」） -->
              <td class="align-top">
                <span class="block truncate text-[13px] font-semibold text-text">
                  {{ c.headingPath || `第 ${c.chunkIndex} 片` }}
                </span>
                <span class="mt-0.5 block text-xs text-text-subtle">#{{ c.chunkIndex }}</span>
              </td>
              <!-- 内容列：2 行截断（设计 §2.4.3 分片预览同规格） -->
              <td class="align-top">
                <p
                  :data-testid="`chunk-content-${c.id}`"
                  class="line-clamp-2 text-sm leading-relaxed text-text"
                >
                  {{ c.content }}
                </p>
              </td>
              <!-- 页码列：起止页码（tabular-nums 全局等宽） -->
              <td class="align-top">
                <span class="text-[13px]">{{ c.startPage }}-{{ c.endPage }} 页</span>
              </td>
            </tr>
          </DataTable>
        </div>

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
