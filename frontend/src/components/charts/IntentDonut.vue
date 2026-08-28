<script setup lang="ts">
/**
 * 反馈意图环形图（设计稿 1.1.1 Top Selling Instructor donut 机制的意图占比复用）
 *
 * 职责：将 feedbacks/stats（FeedbackIntentStat[]）渲染为 SVG donut——
 * 三意图（知识问答/闲聊/未知意图）按「赞+踩总条数」计算占比；
 * 扇区入场描边生长（strokeDasharray 0→终值，逐段 140ms 错峰，设计稿 A22）、
 * hover 加粗高亮（其余段降透明，设计稿 A23）、深底 tooltip 定位在扇区中点角度。
 * 语义替换说明：设计稿该图为「讲师销量占比」（无后端，已丢弃），本组件以真实
 * 意图统计数据复用其视觉机制，无任何假数据。
 *
 * 动效降级：偏好减少动效时跳过描边生长直接呈现终态（A27 对应）。
 *
 * 线程安全注意：组件私有状态（hover/drawn），无跨实例共享可变状态。
 */
import { computed, onScopeDispose, ref, watch } from 'vue'

import { intentMetaOf } from '@/components/charts/intent-meta'
import { prefersReducedMotion } from '@/lib/motion'

import type { FeedbackIntentStat } from '@/lib/types'

const props = defineProps<{
  /** 意图统计（feedbacks/stats 返回，likedCount/dislikedCount 为 Long 字符串；不允许为空数组，空态由调用方收敛 */
  stats: FeedbackIntentStat[]
}>()

/** 环形几何常量（设计稿 A22 原参数）：viewBox 200、半径 72、描边宽 28 */
const RADIUS = 72
/** 圆周长 C = 2π·72 ≈ 452.39（strokeDasharray 归一基准） */
const CIRCUMFERENCE = 2 * Math.PI * RADIUS

/** 扇区视图模型：meta 元数据 + 条数 + 占比 + 起始占比 + tooltip 锚点（扇区中点角度坐标） */
interface DonutSeg {
  meta: ReturnType<typeof intentMetaOf>
  count: number
  pct: number
  startPct: number
  tipX: number
  tipY: number
}

/** 意图稳定排序：按 intent-meta 定义顺序（知识问答 → 闲聊 → 未知意图）过滤在场意图 */
const orderedStats = computed(() => {
  const order = ['knowledge_question', 'chat', 'unknown']
  return [...props.stats].sort((a, b) => order.indexOf(a.intentType) - order.indexOf(b.intentType))
})

/** 扇区数据：count = 赞+踩（Long 字符串转 number 求和），占比与起点按总量换算 */
const segs = computed<DonutSeg[]>(() => {
  const counts = orderedStats.value.map((s) => Number(s.likedCount) + Number(s.dislikedCount))
  const total = counts.reduce((acc, n) => acc + n, 0)
  let startPct = 0
  return orderedStats.value.map((s, i) => {
    const count = counts[i]
    const pct = total > 0 ? (count / total) * 100 : 0
    const seg: DonutSeg = {
      meta: intentMetaOf(s.intentType),
      count,
      pct,
      startPct,
      tipX: 0,
      tipY: 0,
    }
    // tooltip 锚点：扇区中点角度方向的半径中点坐标（viewBox 200 坐标系，转百分比定位）
    const midRad = ((startPct + pct / 2) / 100) * 2 * Math.PI
    seg.tipX = 100 + RADIUS * Math.cos(midRad)
    seg.tipY = 100 + RADIUS * Math.sin(midRad)
    startPct += pct
    return seg
  })
})

/** 总条数（环形中心展示，真实数据） */
const totalCount = computed(() => segs.value.reduce((acc, s) => acc + s.count, 0))

/** 当前 hover 扇区下标（null 表示无 hover，tooltip 隐藏） */
const hover = ref<number | null>(null)

/** 是否已进入描边终态（false 时 dasharray 为 0 C，true 时为终值） */
const drawn = ref(false)
/** 未完成的 rAF 句柄（双层 rAF 确保初帧以零长度描边绘制，过渡才可触发） */
let rafId = 0

/** 取消进行中的动画帧 */
const cancelDraw = () => {
  if (rafId !== 0) {
    cancelAnimationFrame(rafId)
    rafId = 0
  }
}

/** 描边生长：数据变化（含首次挂载）重播；减少动效直接终态 */
const replayDraw = () => {
  cancelDraw()
  if (prefersReducedMotion() || typeof requestAnimationFrame !== 'function') {
    drawn.value = true
    return
  }
  drawn.value = false
  requestAnimationFrame(() => {
    rafId = requestAnimationFrame(() => {
      drawn.value = true
      rafId = 0
    })
  })
}

watch(() => props.stats, replayDraw, { immediate: true })
onScopeDispose(cancelDraw)
</script>

<template>
  <div data-testid="intent-donut" class="flex h-full w-full items-center gap-5">
    <!-- 环形主体：SVG viewBox 200，g 整体逆时针旋转 90° 使扇区从 12 点方向起笔（设计稿 A22） -->
    <div class="relative aspect-square w-[min(235px,60%)] shrink-0">
      <svg viewBox="0 0 200 200" class="h-full w-full" :class="hover !== null ? 'hovering' : ''">
        <g transform="rotate(-90 100 100)">
          <circle
            v-for="(seg, i) in segs"
            :key="seg.meta.value"
            :data-testid="`donut-seg-${i}`"
            class="seg"
            :class="[`seg-${seg.meta.toneIndex}`, hover === i ? 'on' : '']"
            cx="100"
            cy="100"
            :r="RADIUS"
            :stroke-dasharray="
              drawn
                ? `${Math.max((seg.pct / 100) * CIRCUMFERENCE - 2.5, 0.5)} ${CIRCUMFERENCE}`
                : `0 ${CIRCUMFERENCE}`
            "
            :stroke-dashoffset="(-(seg.startPct / 100) * CIRCUMFERENCE).toFixed(2)"
            :style="{ '--sd': `${i * 140}ms` }"
            tabindex="0"
            @mouseenter="hover = i"
            @mouseleave="hover = null"
            @focus="hover = i"
            @blur="hover = null"
          />
        </g>
      </svg>
      <!-- 中心总量（真实数据：全部意图赞踩总条数） -->
      <div class="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
        <span class="text-xl font-extrabold tabular-nums text-text">{{ totalCount }}</span>
        <span class="mt-0.5 text-xs font-medium text-text-muted">总评价条数</span>
      </div>
      <!-- hover tooltip：深底白字，定位在扇区中点角度（设计稿 A23 .donut-tip） -->
      <div
        v-if="hover !== null"
        data-testid="donut-tip"
        class="absolute z-10 -translate-x-1/2 -translate-y-1/2 rounded-lg bg-chart-tooltip px-3 py-[7px] text-xs font-bold whitespace-nowrap text-white"
        :style="{
          left: `${(segs[hover].tipX / 200) * 100}%`,
          top: `${(segs[hover].tipY / 200) * 100}%`,
        }"
      >
        {{ segs[hover].meta.label }} · {{ segs[hover].count }} 条 ·
        {{ Math.round(segs[hover].pct) }}%
      </div>
    </div>
    <!-- 图例：意图标签 + 条数 + 占比（色点与扇区同源，图例 hover 位移反馈照设计稿 A14 形态） -->
    <ul class="min-w-0 flex-1 space-y-2.5" data-testid="donut-legend">
      <li
        v-for="seg in segs"
        :key="`legend-${seg.meta.value}`"
        class="flex items-center gap-2 text-[13px] font-semibold text-text-muted transition-transform duration-200 hover:-translate-y-px"
      >
        <i class="h-2.5 w-2.5 shrink-0 rounded-full" :class="seg.meta.dotClass" />
        <span class="truncate">{{ seg.meta.label }}</span>
        <span class="ml-auto shrink-0 tabular-nums">
          {{ seg.count }} 条 · {{ Math.round(seg.pct) }}%
        </span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
/* 扇区公共：不填充仅描边；描边宽 28（hover 加粗 34，设计稿 A23）；描边长度过渡承载入场生长 */
.seg {
  fill: none;
  stroke-width: 28;
  cursor: pointer;
  transition:
    stroke-dasharray 0.9s var(--ease) var(--sd, 0ms),
    opacity 0.3s ease,
    stroke-width 0.3s ease;
}
/* 扇区色序位：@theme 图表序列令牌（主紫 → 浅紫 → 最浅紫），禁散落硬编码色值 */
.seg-0 {
  stroke: var(--color-chart-series-1);
}
.seg-1 {
  stroke: var(--color-chart-series-2);
}
.seg-2 {
  stroke: var(--color-chart-series-3);
}
/* hover 态：其余扇区降透明聚焦当前段（设计稿 A22 .donut.hovering） */
svg.hovering .seg {
  opacity: 0.35;
}
svg.hovering .seg.on {
  opacity: 1;
  stroke-width: 34;
}
/* 减少动效偏好：跳过描边生长直接呈现终态（CSS 层兜底，与 JS 侧降级双保险） */
@media (prefers-reduced-motion: reduce) {
  .seg {
    transition: none;
  }
}
</style>
