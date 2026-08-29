<script setup lang="ts">
/**
 * 基础多行文本组件（契约 G.2.3 表单模式基线）
 *
 * 职责：统一「Label 上置 + 错误内联 + aria」的 textarea 封装。
 * rows 控制可视行数（缺省 3），默认禁止纵向拖拽调整（保持布局稳定）。
 * 无状态组件，无线程安全诉求。
 */
import { computed, useId } from 'vue'

import { cn } from '@/lib/utils'

const props = withDefaults(
  defineProps<{
    /** 输入值（v-model 绑定） */
    modelValue: string | null | undefined
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
    /** 可视行数（缺省 3） */
    rows?: number
    /** 禁用态 */
    disabled?: boolean
  }>(),
  {
    label: '',
    required: false,
    error: '',
    helper: '',
    placeholder: '',
    rows: 3,
    disabled: false,
  },
)

const emit = defineEmits<{
  /** v-model 回抛 */
  'update:modelValue': [value: string]
}>()

// $attrs（data-testid 等）显式转发到原生 textarea（根为 div，自动继承会落错元素）
defineOptions({ inheritAttrs: false })

/** 自动生成稳定 id：label 与 textarea htmlFor 关联 */
const areaId = useId()
/** 错误/辅助说明 DOM id（aria-describedby 关联目标） */
const describeId = computed(() => `${areaId}-desc`)

/** 输入事件：回抛字符串 */
function onInput(event: Event) {
  emit('update:modelValue', (event.target as HTMLTextAreaElement).value)
}
</script>

<template>
  <div>
    <!-- Label 上置（ABOVE input） -->
    <label v-if="props.label" :for="areaId" class="mb-1.5 block text-sm font-medium text-text">
      {{ props.label }}
      <span v-if="props.required" class="text-danger">*</span>
    </label>
    <textarea
      :id="areaId"
      v-bind="$attrs"
      :value="props.modelValue ?? ''"
      :placeholder="props.placeholder"
      :rows="props.rows"
      :disabled="props.disabled"
      :aria-invalid="props.error ? 'true' : undefined"
      :aria-describedby="props.error || props.helper ? describeId : undefined"
      :class="
        cn(
          'w-full resize-none rounded-xl border bg-surface px-3 py-2 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:ring-2 disabled:cursor-not-allowed disabled:opacity-60',
          props.error
            ? 'border-danger focus:border-danger focus:ring-danger/20'
            : 'border-border focus:border-brand focus:ring-brand/20',
        )
      "
      @input="onInput"
    />
    <!-- 错误内联于字段下方；辅助说明 muted 色有视觉区分 -->
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
