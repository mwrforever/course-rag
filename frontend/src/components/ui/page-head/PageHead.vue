<script setup lang="ts">
/**
 * 页头组件（设计稿 page-head 形态）
 *
 * 职责：统一各视图页头的「主标题 + 副标题 + 右侧动作区」结构，
 * N6~N8 视图迁移时套用（本组件不迁移既有视图，各视图页头仍由自身渲染）。
 * 视觉：h1 22px/800 + 副题 13px muted（设计稿 1.1.2 .page-head）。
 */
const props = withDefaults(
  defineProps<{
    /** 页面主标题（h1 文案，用户可见；不允许为空） */
    title: string
    /** 副标题（页面辅助说明，13px 次级文字；传空串/缺省时不渲染该行） */
    subtitle?: string
  }>(),
  {
    subtitle: '',
  },
)
</script>

<template>
  <!-- 页头骨架：左标题块 + 右动作区（基线对齐，动作区不换行收缩） -->
  <header class="flex flex-wrap items-end justify-between gap-x-4 gap-y-2">
    <div class="min-w-0">
      <h1 class="text-[22px] leading-tight font-extrabold tracking-tight text-text">
        {{ props.title }}
      </h1>
      <!-- 副标题：仅在传入非空文案时渲染 -->
      <p v-if="props.subtitle" class="mt-[5px] text-[13px] text-text-muted">
        {{ props.subtitle }}
      </p>
    </div>
    <!-- 右侧动作区：放置主按钮/下拉等页级操作 -->
    <div class="flex shrink-0 items-center gap-2">
      <slot name="actions" />
    </div>
  </header>
</template>
