<script setup lang="ts">
/**
 * 基础按钮（手拼 shadcn-vue 等价件）
 *
 * 说明：Task 15 CLI 初始化受网络交互限制，本组件按 shadcn-vue 视觉契约手拼
 * （cva 变体 + cn 合并，tokens 走语义层），后续任务可在网络恢复后迁移 reka-ui 版本。
 * 形状锁：按钮 rounded-lg（8px），hover/active 150ms（设计 §2.2/§2.6）。
 */
import { cva, type VariantProps } from 'class-variance-authority'

import { cn } from '@/lib/utils'

// 按钮变体：default 主 CTA（brand 实底 + 品牌投影）/ outline 次操作 / ghost 弱操作 / danger 危险操作
const buttonVariants = cva(
  'inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-lg text-sm font-medium transition-colors duration-150 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand disabled:pointer-events-none disabled:opacity-50',
  {
    variants: {
      variant: {
        default: 'bg-brand text-white shadow-brand hover:bg-brand-strong',
        outline: 'border border-border bg-surface text-text hover:bg-surface-2',
        ghost: 'text-text hover:bg-surface-2',
        danger: 'bg-danger text-white hover:bg-red-700',
      },
      size: {
        default: 'h-9 px-4 py-2',
        sm: 'h-8 px-3 text-xs',
        lg: 'h-10 px-6',
      },
    },
    defaultVariants: {
      variant: 'default',
      size: 'default',
    },
  },
)

// type 缺省 button：避免表单容器内误触提交
const props = withDefaults(
  defineProps<{
    /** 按钮视觉变体：#default 主 CTA / outline 次操作 / ghost 弱操作 / danger 危险操作 */
    variant?: VariantProps<typeof buttonVariants>['variant']
    /** 按钮尺寸：default / sm / lg */
    size?: VariantProps<typeof buttonVariants>['size']
    /** 原生 type：缺省 button（表单内请显式传 submit） */
    type?: 'button' | 'submit' | 'reset'
  }>(),
  {
    variant: 'default',
    size: 'default',
    type: 'button',
  },
)
</script>

<template>
  <button
    :type="props.type"
    :class="cn(buttonVariants({ variant: props.variant, size: props.size }))"
  >
    <slot />
  </button>
</template>
