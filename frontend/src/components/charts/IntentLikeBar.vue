<script setup lang="ts">
/**
 * 意图 × 赞踩堆叠条卡（FeedbackView 原图表库堆叠柱的 CSS 横向堆叠条复刻）
 *
 * 职责：将 feedbacks/stats（FeedbackIntentStat[]）按意图渲染为横向双段堆叠条——
 * 每意图一行：意图色点 + 标签 + 堆叠轨道（赞 success 绿段 + 踩 danger 红段，
 * 段宽按行内占比）+ 计数文本。赞踩语义色与列表评价图标色一致（状态语义非装饰）。
 *
 * 真实性：全部数值来自 feedbacks/stats 接口（likedCount/dislikedCount Long 字符串
 * 转 number），无环比无假数据。
 *
 * 线程安全注意：纯派生渲染，无本地可变状态。
 */
import { computed } from 'vue'

import { intentMetaOf } from '@/components/charts/intent-meta'

import type { FeedbackIntentStat } from '@/lib/types'

const props = defineProps<{
  /** 意图统计（feedbacks/stats 返回；不允许为空数组，空态由调用方收敛） */
  stats: FeedbackIntentStat[]
}>()

/** 行视图模型：意图元数据 + 赞/踩数 + 行内赞占比（轨道段宽） */
interface LikeRow {
  meta: ReturnType<typeof intentMetaOf>
  liked: number
  disliked: number
  likedPct: number
}

/** 意图稳定排序：按 intent-meta 定义顺序（知识问答 → 闲聊 → 未知意图） */
const rows = computed<LikeRow[]>(() => {
  const order = ['knowledge_question', 'chat', 'unknown']
  return [...props.stats]
    .sort((a, b) => order.indexOf(a.intentType) - order.indexOf(b.intentType))
    .map((s) => {
      const liked = Number(s.likedCount)
      const disliked = Number(s.dislikedCount)
      const total = liked + disliked
      return {
        meta: intentMetaOf(s.intentType),
        liked,
        disliked,
        // 行内总量为 0 时赞段占 0（轨道空显示，计数仍透出 0）
        likedPct: total > 0 ? (liked / total) * 100 : 0,
      }
    })
})
</script>

<template>
  <div data-testid="intent-like-bar" class="flex h-full w-full flex-col">
    <!-- 图例行：赞（绿）/ 踩（红）语义色点 -->
    <div class="flex items-center gap-4 text-[13px] font-semibold text-text-muted">
      <span class="flex items-center gap-1.5"
        ><i class="h-2.5 w-2.5 rounded-full bg-success" />赞</span
      >
      <span class="flex items-center gap-1.5"
        ><i class="h-2.5 w-2.5 rounded-full bg-danger" />踩</span
      >
    </div>
    <!-- 意图行：色点 + 标签 + 堆叠轨道 + 计数 -->
    <ul class="mt-3.5 flex-1 space-y-4">
      <li
        v-for="row in rows"
        :key="row.meta.value"
        :data-testid="`intent-like-row-${row.meta.value}`"
        class="flex items-center gap-3"
      >
        <i class="h-2.5 w-2.5 shrink-0 rounded-full" :class="row.meta.dotClass" />
        <span class="w-16 shrink-0 truncate text-[13px] font-semibold text-text-muted">
          {{ row.meta.label }}
        </span>
        <!-- 堆叠轨道：赞段 + 踩段按行内占比分宽；行总量为 0 时保持空槽（不渲染红绿段） -->
        <div class="h-[18px] min-w-0 flex-1 overflow-hidden rounded-full bg-brand-light">
          <div v-if="row.liked + row.disliked > 0" class="flex h-full w-full">
            <div
              class="h-full bg-success transition-[width] duration-500 ease-out"
              :style="{ width: `${row.likedPct}%` }"
            />
            <div class="h-full flex-1 bg-danger" />
          </div>
        </div>
        <!-- 计数：赞 N · 踩 M（真实接口数值，tabular-nums 对齐） -->
        <span class="shrink-0 text-xs font-semibold tabular-nums text-text-muted">
          赞 {{ row.liked }} · 踩 {{ row.disliked }}
        </span>
      </li>
    </ul>
  </div>
</template>
