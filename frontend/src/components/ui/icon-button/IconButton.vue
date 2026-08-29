<script setup lang="ts">
/**
 * 图标按钮组件（契约 G.2.7：页头刷新 icon-button 等）
 *
 * 职责：纯图标触发的方形/圆形按钮，aria-label 与 hover/focus tooltip 同源
 * （label prop 同时驱动两者），保证触控目标 ≥ 36px、焦点可见（focus ring）。
 * 视觉变体：ghost（缺省，次级操作）/ outline（描边）/ brand（品牌实底）。
 * 无自身业务状态，无线程安全诉求。
 */
import { PhSpinnerGap } from '@phosphor-icons/vue'

import { cn } from '@/lib/utils'

const props = withDefaults(
  defineProps<{
    /** 无障碍标签与 tooltip 文案（用户可见动作描述，不允许为空） */
    label: string
    /** 视觉变体：ghost 弱操作（缺省）/ outline 描边 / brand 品牌实底 */
    variant?: 'ghost' | 'outline' | 'brand'
    /** 尺寸：default 36px / sm 32px（触控目标均 ≥ 最小可点尺寸） */
    size?: 'default' | 'sm'
    /** 禁用态（如 refetch 进行中防重复点击） */
    disabled?: boolean
    /** 加载态：spinner 替换图标内容（按钮同时禁用） */
    loading?: boolean
    /** 原生 type，缺省 button */
    type?: 'button' | 'submit' | 'reset'
  }>(),
  {
    variant: 'ghost',
    size: 'default',
    disabled: false,
    loading: false,
    type: 'button',
  },
)

const emit = defineEmits<{
  /** 点击（loading/disabled 态不抛出） */
  click: [event: MouseEvent]
}>()

// $attrs（data-testid 等）显式转发到内部 button（根为 tooltip 容器 span，自动继承会落错元素）
defineOptions({ inheritAttrs: false })

/** 点击分发：loading/disabled 态吞掉（防重复触发） */
function onClick(event: MouseEvent) {
  if (props.disabled || props.loading) return
  emit('click', event)
}
</script>

<template>
  <!-- tooltip 悬浮层：group hover/focus-within 显隐，role=tooltip 供读屏关联 -->
  <span class="group relative inline-flex">
    <button
      :type="props.type"
      v-bind="$attrs"
      :aria-label="props.label"
      :disabled="props.disabled || props.loading"
      :class="
        cn(
          'inline-grid place-items-center rounded-lg text-text-muted transition-colors duration-150 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand disabled:pointer-events-none disabled:opacity-50',
          props.size === 'sm' ? 'h-8 w-8' : 'h-9 w-9',
          props.variant === 'ghost' && 'hover:bg-surface-2 hover:text-text',
          props.variant === 'outline' &&
            'border border-border bg-surface text-text hover:bg-surface-2',
          props.variant === 'brand' && 'bg-brand text-white shadow-brand hover:bg-brand-strong',
        )
      "
      @click="onClick"
    >
      <!-- loading 态以 spinner 替换图标；否则渲染调用方图标插槽 -->
      <PhSpinnerGap v-if="props.loading" class="h-4 w-4 animate-spin" aria-hidden="true" />
      <slot v-else />
    </button>
    <!-- tooltip：紫黑深底小浮签，hover/focus-visible 显隐（动效 150ms，仅反馈性过渡） -->
    <span
      role="tooltip"
      class="pointer-events-none absolute -top-9 left-1/2 z-50 -translate-x-1/2 rounded-lg bg-ink-900 px-2.5 py-1 text-xs whitespace-nowrap text-white opacity-0 transition-opacity duration-150 group-hover:opacity-100 group-focus-within:opacity-100"
    >
      {{ props.label }}
    </span>
  </span>
</template>
