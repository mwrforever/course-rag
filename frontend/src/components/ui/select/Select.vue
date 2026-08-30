<script setup lang="ts">
/**
 * 基础下拉选择组件（契约 G.2.3 表单模式基线）
 *
 * 职责：统一「Label 上置 + 错误内联 + aria」的原生 select 封装。
 * - 选项经 options prop（{value,label} 数组）声明式传入，v-model 承载 value；
 * - error 非空时描红 + 字段下方红字（aria-invalid / aria-describedby 关联）；
 * - 默认选项（placeholder 性质的空值 option）由调用方在 options 首位传空 value 提供。
 * 远程搜索场景请用 remote-select 组件（契约 E），本组件仅承载静态预置选项。
 */
import { computed, useId } from 'vue'
import { PhCaretDown } from '@phosphor-icons/vue'

import { cn } from '@/lib/utils'

/** 下拉选项（value 为 string | number，label 为展示文案） */
export interface SelectOption {
  value: string | number
  label: string
}

const props = withDefaults(
  defineProps<{
    /** 选中值（v-model 绑定，空串 = 未选） */
    modelValue: string | number
    /** 字段标签（上置；传空串时不渲染 label 行） */
    label?: string
    /** 是否必填（label 后追加红色 * 指示） */
    required?: boolean
    /** 错误文案（非空时字段下方红字内联展示，选择框描红） */
    error?: string
    /** 静态选项集（含默认空选项时由调用方置于首位） */
    options: SelectOption[]
    /** 禁用态 */
    disabled?: boolean
    /** 原生 name 属性 */
    name?: string
  }>(),
  {
    label: '',
    required: false,
    error: '',
    disabled: false,
    name: '',
  },
)

const emit = defineEmits<{
  /** v-model 回抛（change 语义：选中即提交） */
  'update:modelValue': [value: string]
}>()

// $attrs（data-testid 等）显式转发到原生 select（根为 div，自动继承会落错元素）
defineOptions({ inheritAttrs: false })

/** 自动生成稳定 id：label 与 select htmlFor 关联 */
const selectId = useId()
/** 错误提示 DOM id（aria-describedby 关联目标） */
const errorId = computed(() => `${selectId}-error`)

/** 选中变化：回抛字符串 value（number 选项 value 由调用方归一） */
function onChange(event: Event) {
  emit('update:modelValue', (event.target as HTMLSelectElement).value)
}
</script>

<template>
  <div>
    <!-- Label 上置（ABOVE input） -->
    <label v-if="props.label" :for="selectId" class="mb-1.5 block text-sm font-medium text-text">
      {{ props.label }}
      <span v-if="props.required" class="text-danger">*</span>
    </label>
    <!-- 原生 select：CaretDown 箭头叠加原生交互（appearance-none 后自绘指示） -->
    <div class="relative">
      <select
        :id="selectId"
        v-bind="$attrs"
        :name="props.name || undefined"
        :value="props.modelValue"
        :disabled="props.disabled"
        :aria-invalid="props.error ? 'true' : undefined"
        :aria-describedby="props.error ? errorId : undefined"
        :class="
          cn(
            'h-10 w-full appearance-none rounded-xl border bg-surface px-3 pr-9 text-sm text-text outline-none transition-colors duration-150 focus:ring-2 disabled:cursor-not-allowed disabled:opacity-60',
            props.error
              ? 'border-danger focus:border-danger focus:ring-danger/20'
              : 'border-border focus:border-brand focus:ring-brand/20',
          )
        "
        @change="onChange"
      >
        <option v-for="opt in props.options" :key="opt.value" :value="opt.value">
          {{ opt.label }}
        </option>
      </select>
      <!-- 下拉指示箭头（Phosphor CaretDown，pointer-events-none 不拦截原生交互） -->
      <PhCaretDown
        aria-hidden="true"
        class="pointer-events-none absolute top-1/2 right-3 h-4 w-4 -translate-y-1/2 text-text-subtle"
      />
    </div>
    <!-- 错误内联于字段下方 -->
    <p v-if="props.error" :id="errorId" class="mt-1 text-xs text-danger">{{ props.error }}</p>
  </div>
</template>
