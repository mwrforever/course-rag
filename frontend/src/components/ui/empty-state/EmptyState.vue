<script setup lang="ts">
/**
 * 空态组件（设计稿空态形态：图标 + 标题 + 描述 + 可选动作）
 *
 * 职责：列表/检索无数据时的统一空态占位（N6~N8 视图套用）。
 * 各视图既有空态文案断言密集（如「该会话暂无消息记录」），本组件不迁移既有视图，
 * 仅提供标准结构：紫系图标圆 + 标题 + 描述 + 动作插槽。
 */
import { PhPackage } from '@phosphor-icons/vue'

const props = withDefaults(
  defineProps<{
    /** 空态标题（如「暂无文档」；用户可见，不允许为空） */
    title: string
    /** 空态描述（引导性说明，如上传指引）；传空串/缺省时不渲染该行 */
    description?: string
  }>(),
  {
    description: '',
  },
)
</script>

<template>
  <!-- 空态容器：居中纵排，上下留白适配卡片/表格空腔 -->
  <div class="flex flex-col items-center justify-center gap-1.5 px-6 py-12 text-center">
    <!-- 图标圆：brand-soft 浅紫底 + brand 图标色；图标插槽可换业务语义图标 -->
    <span class="mb-2 grid h-12 w-12 place-items-center rounded-full bg-brand-soft text-brand">
      <slot name="icon">
        <PhPackage class="h-6 w-6" aria-hidden="true" />
      </slot>
    </span>
    <p class="text-[15px] font-semibold text-text">{{ props.title }}</p>
    <!-- 描述行：仅传入非空文案时渲染 -->
    <p v-if="props.description" class="max-w-sm text-[13px] leading-relaxed text-text-muted">
      {{ props.description }}
    </p>
    <!-- 动作插槽：主引导按钮（如「上传文档」），可选 -->
    <div v-if="$slots.action" class="mt-3">
      <slot name="action" />
    </div>
  </div>
</template>
