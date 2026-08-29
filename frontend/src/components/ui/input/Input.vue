<script setup lang="ts">
/**
 * 基础文本输入组件（契约 G.2.3 表单模式基线）
 *
 * 职责：统一「Label 上置 + 错误内联于字段下方 + aria 关联」的表单输入形态。
 * - Label 渲染于输入框上方（ABOVE input，design-taste 规范）；
 * - error 非空时输入框描红 + 字段下方红字提示，并关联 aria-invalid / aria-describedby；
 * - helper（辅助说明）与 error 互斥展示，有视觉区分（muted 色）；
 * - placeholder 仅作格式提示，不做标签用途。
 * 无状态组件，无线程安全诉求。
 */
import { computed, useId } from 'vue'

import { cn } from '@/lib/utils'

const props = withDefaults(
  defineProps<{
    /** 输入值（v-model 绑定） */
    modelValue: string | number | null | undefined
    /** 字段标签（上置；传空串时不渲染 label 行） */
    label?: string
    /** 是否必填（label 后追加红色 * 指示） */
    required?: boolean
    /** 错误文案（非空时字段下方红字内联展示，输入框描红） */
    error?: string
    /** 辅助说明文案（muted 色，与 error 互斥：error 优先展示） */
    helper?: string
    /** 占位提示（仅格式提示，不做标签） */
    placeholder?: string
    /** 原生 input type，缺省 text */
    type?: string
    /** 原生 name 属性（表单语义） */
    name?: string
    /** 禁用态 */
    disabled?: boolean
    /** 只读态 */
    readonly?: boolean
  }>(),
  {
    label: '',
    required: false,
    error: '',
    helper: '',
    placeholder: '',
    type: 'text',
    name: '',
    disabled: false,
    readonly: false,
  },
)

const emit = defineEmits<{
  /** v-model 回抛 */
  'update:modelValue': [value: string]
}>()

// $attrs（data-testid / list 等）显式转发到原生 input（根为 div，自动继承会落错元素）
defineOptions({ inheritAttrs: false })

/** 自动生成稳定 id：label 与输入框 htmlFor 关联 + aria-describedby 锚点 */
const inputId = useId()
/** 错误/辅助说明 DOM id（aria-describedby 关联目标） */
const describeId = computed(() => `${inputId}-desc`)

/** 输入事件：统一回抛字符串（number 型 input 的 v-model.number 由调用方决定） */
function onInput(event: Event) {
  emit('update:modelValue', (event.target as HTMLInputElement).value)
}
</script>

<template>
  <div>
    <!-- Label 上置（ABOVE input）：required 追加红色 * 指示 -->
    <label v-if="props.label" :for="inputId" class="mb-1.5 block text-sm font-medium text-text">
      {{ props.label }}
      <span v-if="props.required" class="text-danger">*</span>
    </label>
    <input
      :id="inputId"
      v-bind="$attrs"
      :name="props.name || undefined"
      :type="props.type"
      :value="props.modelValue ?? ''"
      :placeholder="props.placeholder"
      :disabled="props.disabled"
      :readonly="props.readonly"
      :aria-invalid="props.error ? 'true' : undefined"
      :aria-describedby="props.error || props.helper ? describeId : undefined"
      :class="
        cn(
          'h-10 w-full rounded-xl border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:ring-2 disabled:cursor-not-allowed disabled:opacity-60',
          props.error
            ? 'border-danger focus:border-danger focus:ring-danger/20'
            : 'border-border focus:border-brand focus:ring-brand/20',
        )
      "
      @input="onInput"
    />
    <!-- 错误内联于字段下方（红字）；辅助说明 muted 色有视觉区分 -->
    <p
      v-if="props.error || props.helper"
      :id="describeId"
      class="mt-1 text-xs"
      :class="props.error ? 'text-danger' : 'text-text-subtle'"
    >
      {{ props.error || props.helper }}
    </p>
  </div>
</template>
