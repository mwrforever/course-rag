<script setup lang="ts">
/**
 * 反馈趋势柱状图（设计稿 1.1.1 图表区 bar 形态的纯 CSS 复刻）
 *
 * 职责：将 feedback/trend 的单序列（每日反馈数）渲染为 CSS 柱状图——
 * div 高度承载数值、紫系渐变柱身、y 轴刻度 + 虚线网格、柱 hover tooltip、
 * 入场生长动画（高度 0 → 目标，逐柱 70ms 错峰，设计稿 A13）。
 * 依赖 design tokens（@theme）取色：柱身 chart-series-1→brand-strong 渐变、
 * 网格 chart-axis、刻度文字 text-subtle、tooltip 深底 chart-tooltip。
 *
 * 动效降级：用户偏好减少动效时跳过生长动画直接呈现终高（无障碍底线，A27 对应）。
 *
 * 线程安全注意：组件私有状态（active/grown），无跨实例共享可变状态。
 */
import { computed, onScopeDispose, ref, watch } from 'vue'

import { prefersReducedMotion } from '@/lib/motion'

import type { FeedbackTrendItem } from '@/lib/types'

const props = defineProps<{
  /** 趋势序列（feedback/trend 返回，date 为 ISO 日期串、count 为 Long 字符串；不允许为空数组，空态由调用方收敛 */
  items: FeedbackTrendItem[]
}>()

/** 柱行视图模型：label 为 MM-DD 短日期，value 为 count 字符串转 number，hPct 为相对满刻度的百分比高度 */
interface TrendRow {
  label: string
  value: number
  hPct: number
}

/** 满刻度取整：最大值向上取到 4 的倍数（保证刻度显示为整数），下限 4 防全零序列无高度基准 */
const NICE_MAX = computed(() => {
  const max = props.items.reduce((m, t) => Math.max(m, Number(t.count)), 0)
  return Math.max(4, Math.ceil(max / 4) * 4)
})

/** y 轴刻度值（自上而下：满 / 3/4 / 1/2 / 1/4 / 0，设计稿五档刻度） */
const ticks = computed(() =>
  [1, 0.75, 0.5, 0.25, 0].map((ratio) => Math.round(NICE_MAX.value * ratio)),
)

/** 柱行数据：date 截取 MM-DD，count 字符串转 number 后按满刻度换算百分比高度 */
const rows = computed<TrendRow[]>(() =>
  props.items.map((t) => ({
    label: t.date.slice(5),
    value: Number(t.count),
    hPct: (Number(t.count) / NICE_MAX.value) * 100,
  })),
)

/** x 轴标签抽稀步长：序列长于 14（30 天档）时按约 6 等分抽稀，避免标签挤压 */
const labelStep = computed(() => (props.items.length > 14 ? Math.ceil(props.items.length / 6) : 1))

/** 当前 hover 柱下标（null 表示无 hover，tooltip 隐藏） */
const active = ref<number | null>(null)

/** tooltip 水平定位：hover 柱的中点位置（百分比），首尾柱钳制在 10%~90% 防溢出裁切 */
const tipLeft = computed(() => {
  if (active.value === null) return '50%'
  const n = rows.value.length
  return `${Math.min(90, Math.max(10, ((active.value + 0.5) / n) * 100))}%`
})

/** tooltip 文案：短日期 + 反馈条数 */
const tipText = computed(() => {
  if (active.value === null) return ''
  const row = rows.value[active.value]
  return `${row.label} · ${row.value} 条`
})

// ====================================================================
// 入场生长动画（设计稿 A13：高度 0 → var(--h)，逐柱 --d = i*70ms 错峰）
// ====================================================================

/** 是否已进入生长终态（true 时柱高取 var(--h)，false 时保持 0） */
const grown = ref(false)
/** 未完成的 rAF 句柄（0 表示空闲；双层 rAF 确保初帧先以 0 高度绘制，过渡才可触发） */
let rafId = 0

/** 取消进行中的动画帧（数据切换重播与组件卸载共用） */
const cancelGrow = () => {
  if (rafId !== 0) {
    cancelAnimationFrame(rafId)
    rafId = 0
  }
}

/** 重播生长：先归零再双层 rAF 后进入终态（数据切换时柱群重新生长一次） */
const replayGrow = () => {
  cancelGrow()
  if (prefersReducedMotion() || typeof requestAnimationFrame !== 'function') {
    grown.value = true
    return
  }
  grown.value = false
  requestAnimationFrame(() => {
    rafId = requestAnimationFrame(() => {
      grown.value = true
      rafId = 0
    })
  })
}

// 序列变化（含首次挂载 immediate）即重播；卸载清理未完成帧
watch(() => props.items, replayGrow, { immediate: true })
onScopeDispose(cancelGrow)
</script>

<template>
  <!-- 图表根：flex 纵向铺满父容器（父容器给定高度），绘制区 flex-1 -->
  <div data-testid="trend-chart" class="flex h-full w-full flex-col">
    <!-- 绘制区：相对定位承接网格/柱群/tooltip；左留 44px 给 y 轴刻度（设计稿 .plot） -->
    <div class="relative ml-11 flex-1">
      <!-- y 轴刻度：五档定位于左侧外挂（设计稿 .y-axis） -->
      <div class="absolute inset-y-0 -left-11 w-10">
        <span
          v-for="(tick, i) in ticks"
          :key="`tick-${i}`"
          class="absolute right-1 -translate-y-1/2 text-[11.5px] font-semibold tabular-nums text-text-subtle"
          :style="{ top: `${i * 25}%` }"
        >
          {{ tick }}
        </span>
      </div>
      <!-- 网格线：四虚一实（顶部三档虚线 + 3/4 档虚线 + 底线实线，设计稿 .gridlines） -->
      <div class="pointer-events-none absolute inset-0">
        <i
          v-for="i in 5"
          :key="`grid-${i}`"
          class="absolute inset-x-0 border-t border-chart-axis"
          :class="i === 5 ? 'border-solid' : 'border-dashed'"
          :style="{ top: `${(i - 1) * 25}%` }"
        />
      </div>
      <!-- 柱群：flex 底对齐；grow 类切换驱动生长过渡（mouseleave 不冒泡，逐柱挂载清理 hover 态） -->
      <div class="absolute inset-0 flex items-end" :class="grown ? 'grow' : ''">
        <div
          v-for="(row, i) in rows"
          :key="`bar-${row.label}`"
          :data-testid="`trend-bar-${i}`"
          class="relative flex h-full min-w-0 flex-1 items-end justify-center"
          @mouseenter="active = i"
          @mouseleave="active = null"
        >
          <!-- 柱体：宽度 min(30px,58%)、紫系渐变、圆角顶；--h 终高 / --d 逐柱错峰延迟 -->
          <div
            class="bar w-[min(30px,58%)] rounded-t-[7px] rounded-b-[3px]"
            :style="{ '--h': `${row.hPct}%`, '--d': `${i * 70}ms` }"
          />
        </div>
      </div>
      <!-- hover tooltip：深底白字 + 小三角箭头，跟随 hover 柱中点（设计稿 .ov-tip） -->
      <div
        v-if="active !== null"
        data-testid="trend-tip"
        class="chart-tip absolute top-1 z-10 -translate-x-1/2 rounded-lg bg-chart-tooltip px-[11px] py-[7px] text-xs font-bold whitespace-nowrap text-white"
        :style="{ left: tipLeft }"
      >
        {{ tipText }}
      </div>
    </div>
    <!-- x 轴标签：30 天档按步长抽稀（隐藏非锚点标签但保留占位，柱位对齐不漂移） -->
    <div class="mt-3 ml-11 flex">
      <span
        v-for="(row, i) in rows"
        :key="`x-${row.label}`"
        class="flex-1 truncate text-center text-[12.5px] font-semibold text-text-muted"
        :class="i % labelStep !== 0 ? 'invisible' : ''"
      >
        {{ row.label }}
      </span>
    </div>
  </div>
</template>

<style scoped>
/* 柱体：初始高度 0，grow 后过渡到终高 var(--h)；高度曲线照设计稿 A13，逐柱 --d 错峰 */
.bar {
  height: 0;
  /* 紫系纵向渐变：主紫 → 深紫（@theme 图表序列令牌，禁散落硬编码色值） */
  background: linear-gradient(180deg, var(--color-chart-series-1), var(--color-brand-strong));
  transition:
    height 0.9s cubic-bezier(0.22, 1, 0.36, 1) var(--d, 0ms),
    filter 0.25s ease;
}
.grow .bar {
  height: var(--h);
}
/* 柱列 hover 提亮（设计稿 .bar-col:hover .bar） */
.bar:hover {
  filter: brightness(1.08);
}
/* tooltip 小三角（深底同色，居中下缘） */
.chart-tip::after {
  content: '';
  position: absolute;
  top: 100%;
  left: 50%;
  transform: translateX(-50%);
  border: 5px solid transparent;
  border-top-color: var(--color-chart-tooltip);
}
/* 减少动效偏好：跳过生长动画直接呈现终态（CSS 层兜底，与 JS 侧降级双保险） */
@media (prefers-reduced-motion: reduce) {
  .bar {
    height: var(--h);
    transition: none;
  }
}
</style>
