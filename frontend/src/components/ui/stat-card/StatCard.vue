<script setup lang="ts">
/**
 * 统计卡组件（设计稿 1.1.2 stat-card 形态）
 *
 * 职责：仪表盘 KPI 卡的统一造型——lav 紫白底（brand-light）+ 白底圆形图标 +
 * 27px/800 大数值（可选 count-up 数字滚动）+ 标签；hover 弹簧上浮（N6 仪表盘迁移时套用）。
 * 依赖 useCountUp 实现数字滚动（1700ms easeOutCubic，见 composables/use-count-up.ts）；
 * 偏好减少动效时 useCountUp 自动降级为直接呈现终值。
 */
import { computed, toRef } from 'vue'

import { useCountUp } from '@/composables/use-count-up'

/** 图标圆色系（对应设计稿 c-purple/c-green/c-amber/c-rose 四色系） */
type StatTone = 'brand' | 'success' | 'warning' | 'danger'

const props = withDefaults(
  defineProps<{
    /** 卡片标签（统计口径名，如「文档总数」；用户可见，不允许为空） */
    label: string
    /** 统计数值：number 且 countUp 开启时滚动展示；string 原样呈现（如 "98%"） */
    value: number | string
    /** 是否启用数字滚动（仅 value 为 number 时生效；缺省 false 直接呈现） */
    countUp?: boolean
    /** 图标圆色系：brand 主紫 / success 绿 / warning 琥珀 / danger 玫红（缺省 brand） */
    tone?: StatTone
    /** 数字滚动时长（毫秒）；缺省 1700（设计稿 A2），透传 useCountUp */
    duration?: number
    /** 数值格式化：入参当前整数，返回展示串；缺省 en-US 千分位（设计稿 A2 同款） */
    format?: (value: number) => string
  }>(),
  {
    countUp: false,
    tone: 'brand',
    duration: undefined,
    format: undefined,
  },
)

/** 色系 → 图标/趋势文字的颜色类（Tailwind 静态字面量，禁拼接） */
const TONE_TEXT: Record<StatTone, string> = {
  brand: 'text-brand',
  success: 'text-success',
  warning: 'text-warning',
  danger: 'text-danger',
}

/** count-up 滚动值（响应式；countUp 关闭或 value 非 number 时不启用） */
const animated = useCountUp(
  toRef(() => (props.countUp && typeof props.value === 'number' ? props.value : 0)),
  {
    duration: props.duration,
  },
)

/** 数值展示串：滚动中取 animated 当前值，终态/非数字取目标（保证结束态精确等于目标值） */
const display = computed(() => {
  if (typeof props.value !== 'number') {
    return props.value
  }
  if (!props.countUp) {
    return props.format ? props.format(props.value) : props.value.toLocaleString('en-US')
  }
  return props.format ? props.format(animated.value) : animated.value.toLocaleString('en-US')
})
</script>

<template>
  <!-- 卡体：lav 底 + 16px 圆角（报告第二章「卡片统一 16px」）；hover 上浮见 scoped 样式 -->
  <div class="stat-card rounded-2xl bg-brand-light px-[22px] py-5">
    <!-- 标签行：统计口径名（右侧预留趋势/角标插槽位） -->
    <div class="flex items-center justify-between gap-2">
      <h3 class="text-[15px] font-semibold text-text-muted">{{ props.label }}</h3>
      <slot name="label-extra" />
    </div>
    <!-- 主数值区：白底圆形图标 + 大数值（27px/800 紧字距，设计稿 stat-value） -->
    <div class="mt-3.5 mb-2.5 flex items-center gap-[13px]">
      <span
        class="stat-icon grid h-[46px] w-[46px] shrink-0 place-items-center rounded-full bg-surface shadow-xs"
        :class="TONE_TEXT[props.tone]"
      >
        <!-- 图标插槽：调用方放 Phosphor 图标（如 <PhBook />），缺省不渲染图标 -->
        <slot name="icon" />
      </span>
      <p class="text-[27px] font-extrabold tracking-tight text-text tabular-nums">
        {{ display }}
      </p>
    </div>
    <!-- 附加行：趋势/说明文案等（可选） -->
    <div v-if="$slots.meta" class="text-[12.5px]">
      <slot name="meta" />
    </div>
  </div>
</template>

<style scoped>
/* 卡片 hover：弹簧上浮 + 紫调投影（设计稿 A12 stat-card:hover，曲线/阴影走令牌） */
.stat-card {
  transition:
    transform 0.35s var(--spring),
    box-shadow 0.35s ease;
}
.stat-card:hover {
  transform: translateY(-6px);
  box-shadow: var(--shadow-brand-hover);
}

/* 图标圆 hover 联动：轻转 + 放大（设计稿 A12 stat-icon，45 deg spring 的一半长度内完成） */
.stat-card:hover .stat-icon {
  transform: rotate(-8deg) scale(1.12);
  transition: transform 0.45s var(--spring);
}
</style>
