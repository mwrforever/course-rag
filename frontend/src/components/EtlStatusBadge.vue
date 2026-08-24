<script setup lang="ts">
/**
 * ETL 状态徽章（设计 §2.5 状态可视化体系，B 端共享组件）
 *
 * 从 Task 17 仪表盘内联映射抽取：八态变体（PENDING 中性 / PARSING·PARSED 蓝 /
 * CHUNKING·CHUNKED 紫 / EMBEDDING amber / INDEXED emerald / FAILED red）+
 * 工作态 12px spinner + 终态图标（INDEXED ✓ / FAILED ✗）+ FAILED 错误详情展开
 * （mono 13px，设计 §2.5「FAILED 行内可展开错误」）。
 *
 * 消费方：文档列表 / 文档详情时间线 / 仪表盘最近文档三处统一走本组件，
 * 保证状态语义色全局唯一（禁止各页复制映射）。
 *
 * 线程安全注意：无共享可变状态；展开态为组件实例私有 ref，多行渲染互不影响。
 */
import { ref } from 'vue'
import { PhCheck, PhSpinnerGap, PhX } from '@phosphor-icons/vue'

import { Badge } from '@/components/ui/badge'

import type { DocumentParseStatus } from '@/lib/types'

// defineProps 返回值不赋值：模板直接使用 status/errorMessage 顶层绑定（script setup 解构语义）
withDefaults(
  defineProps<{
    /** 文档解析状态（设计 §2.5 八态） */
    status: DocumentParseStatus
    /** FAILED 时的错误详情：点击徽章展开 mono 13px 错误文本（空串不渲染展开区） */
    errorMessage?: string
  }>(),
  { errorMessage: '' },
)

/** FAILED 错误详情展开开关（设计 §2.5：点击徽章展开/收起） */
const errorExpanded = ref(false)

/**
 * 状态 → Badge 语义变体（设计 §2.5 明细）
 *
 * @param status 文档解析状态
 * @returns Badge variant 名（default/brand/violet/warning/success/danger）
 */
function statusVariant(status: DocumentParseStatus) {
  switch (status) {
    case 'PARSING':
    case 'PARSED':
      return 'brand'
    case 'CHUNKING':
    case 'CHUNKED':
      return 'violet'
    case 'EMBEDDING':
      return 'warning'
    case 'INDEXED':
      return 'success'
    case 'FAILED':
      return 'danger'
    default:
      return 'default'
  }
}

/**
 * 激活工作态判定：仅 PARSING/CHUNKING/EMBEDDING 三态带 spinner
 * （设计 §2.5：非终态 Badge 内置 12px spinner；PARSED/CHUNKED 为阶段完成态无 spinner）
 */
function isProcessing(status: DocumentParseStatus): boolean {
  return status === 'PARSING' || status === 'CHUNKING' || status === 'EMBEDDING'
}
</script>

<template>
  <span class="inline-flex flex-col items-start gap-1">
    <!-- FAILED + 有错误详情：徽章切换为可点击按钮，点击展开/收起错误区 -->
    <button
      v-if="status === 'FAILED' && errorMessage"
      type="button"
      data-testid="etl-badge-toggle"
      :aria-expanded="errorExpanded"
      aria-label="展开/收起解析失败原因"
      class="cursor-pointer rounded-full focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand"
      @click="errorExpanded = !errorExpanded"
    >
      <Badge :variant="statusVariant(status)">
        <PhX class="h-3 w-3" />
        {{ status }}
      </Badge>
    </button>
    <!-- 常规态徽章：工作态 spinner / INDEXED ✓ / FAILED ✗ -->
    <Badge v-else :variant="statusVariant(status)" data-testid="etl-badge">
      <PhSpinnerGap v-if="isProcessing(status)" class="h-3 w-3 animate-spin" />
      <PhCheck v-else-if="status === 'INDEXED'" class="h-3 w-3" />
      <PhX v-else-if="status === 'FAILED'" class="h-3 w-3" />
      {{ status }}
    </Badge>
    <!-- FAILED 错误详情展开区（设计 §2.5：mono 13px 显 errorMessage） -->
    <p
      v-if="status === 'FAILED' && errorMessage && errorExpanded"
      data-testid="etl-error-message"
      class="max-w-72 break-all rounded-md border border-danger/20 bg-red-50 px-2 py-1 font-mono text-[13px] leading-relaxed text-danger"
    >
      {{ errorMessage }}
    </p>
  </span>
</template>
