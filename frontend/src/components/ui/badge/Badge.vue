<script setup lang="ts">
/**
 * 状态徽章（手拼 shadcn-vue 等价件）
 *
 * 说明：同 Button（Task 15 CLI 初始化网络受限，按 shadcn-vue 视觉契约手拼）。
 * 设计 §2.2：Badge 11/600 大写 tracking-wider，造型 pill；variant 覆盖状态语义色。
 * 具体状态映射（ETL 八态/修正双色等）在各业务页面按设计 §2.5 明细落地。
 */
import { cva, type VariantProps } from 'class-variance-authority'

import { cn } from '@/lib/utils'

const badgeVariants = cva(
  'inline-flex items-center gap-1 whitespace-nowrap rounded-full border px-2 py-0.5 text-[11px] font-semibold uppercase tracking-wider',
  {
    variants: {
      variant: {
        default: 'border-transparent bg-slate-100 text-slate-500',
        brand: 'border-transparent bg-brand-soft text-brand-strong',
        success: 'border-transparent bg-emerald-50 text-emerald-600',
        danger: 'border-transparent bg-red-50 text-red-600',
        warning: 'border-transparent bg-amber-50 text-amber-600',
        info: 'border-transparent bg-sky-50 text-sky-600',
        // violet：分片状态 CHUNKING/CHUNKED（设计 §2.5 状态可视化体系）
        violet: 'border-transparent bg-violet-50 text-violet-600',
        outline: 'border-border bg-surface text-text-muted',
      },
    },
    defaultVariants: {
      variant: 'default',
    },
  },
)

const props = withDefaults(
  defineProps<{
    /** 徽章语义变体：default 中性 / brand 强调 / success / danger / warning / info / outline */
    variant?: VariantProps<typeof badgeVariants>['variant']
  }>(),
  {
    variant: 'default',
  },
)
</script>

<template>
  <span :class="cn(badgeVariants({ variant: props.variant }))">
    <slot />
  </span>
</template>
