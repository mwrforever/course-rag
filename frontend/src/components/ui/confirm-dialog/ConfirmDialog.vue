<script setup lang="ts">
/**
 * 确认弹窗组件（设计稿弹窗形态重绘：紫黑遮罩 + 16px 圆角卡 + 轻紫投影）
 *
 * 职责：危险/重要操作的二次确认标准壳——标题 + 描述 + 取消/确认按钮，
 * Esc 与遮罩点击触发取消；确认按钮支持 loading 态（异步删除防重复提交）。
 * 项目现状：各视图内联确认弹窗（confirm-delete/confirm-user-del 等 testid 契约密集），
 * 本组件供 N6~N8 视图重绘时套用，不迁移既有视图。
 *
 * 契约说明：$attrs（如 data-testid="confirm-delete"）显式转发到确认按钮，
 * 与既有视图「testid 落在确认按钮」的选择器契约保持一致。
 */
import { PhSpinnerGap } from '@phosphor-icons/vue'

import { Button } from '@/components/ui/button'

defineOptions({
  // attrs 转发确认按钮（testid 契约），不落遮罩根元素
  inheritAttrs: false,
})

const props = withDefaults(
  defineProps<{
    /** 受控开合（v-model:open）：false 时不渲染任何 DOM */
    open: boolean
    /** 弹窗标题（如「删除知识库」；用户可见，不允许为空） */
    title: string
    /** 描述文案（后果说明，如「删除后不可恢复」）；传空串/缺省时不渲染该行 */
    description?: string
    /** 确认按钮文案；缺省「确认」 */
    confirmText?: string
    /** 取消按钮文案；缺省「取消」 */
    cancelText?: string
    /** 确认按钮色系：danger 破坏性操作（默认）/ brand 常规确认 */
    tone?: 'danger' | 'brand'
    /** 确认中（按钮 spinner + 禁用，防重复提交）；缺省 false */
    loading?: boolean
  }>(),
  {
    description: '',
    confirmText: '确认',
    cancelText: '取消',
    tone: 'danger',
    loading: false,
  },
)

const emit = defineEmits<{
  /** 开合状态变化（v-model:open；取消/遮罩点击/Esc 关闭时回抛 false） */
  'update:open': [open: boolean]
  /** 确认（loading 态下不再抛出，防重复提交） */
  confirm: []
  /** 取消（Esc / 遮罩点击 / 取消按钮） */
  cancel: []
}>()

/** 关闭弹窗（统一走 v-model:open 回抛） */
const close = () => {
  emit('update:open', false)
}

/** 取消：回抛 cancel 并关闭 */
const onCancel = () => {
  emit('cancel')
  close()
}

/** 确认：loading 态吞掉（防重复提交）；确认后不自动关闭，由调用方随异步结果控制 */
const onConfirm = () => {
  if (props.loading) {
    return
  }
  emit('confirm')
}
</script>

<template>
  <!-- 遮罩层：紫黑半透明（overlay 令牌）+ 居中布局；Esc/遮罩自点 = 取消 -->
  <div
    v-if="props.open"
    class="fixed inset-0 z-50 flex animate-fade-in items-center justify-center bg-overlay p-4"
    role="dialog"
    aria-modal="true"
    :aria-label="props.title"
    @keydown.esc="onCancel"
    @click.self="onCancel"
  >
    <!-- 弹窗卡：16px 圆角 + 紫调投影 + 菜单级入场动画 -->
    <div class="w-full max-w-[440px] animate-menu-in rounded-2xl bg-surface p-6 shadow-lg">
      <h2 class="text-base font-semibold text-text">{{ props.title }}</h2>
      <p v-if="props.description" class="mt-2 text-sm leading-relaxed text-text-muted">
        {{ props.description }}
      </p>
      <!-- 动作区：取消在左（弱），确认在右（强） -->
      <div class="mt-6 flex justify-end gap-2">
        <Button variant="outline" data-testid="cancel-action" @click="onCancel">
          {{ props.cancelText }}
        </Button>
        <!-- $attrs 转发（data-testid 等）落在确认按钮且可覆盖默认值（v-bind 置后生效），对齐既有视图选择器契约 -->
        <Button
          data-testid="confirm-action"
          v-bind="$attrs"
          :variant="props.tone === 'danger' ? 'danger' : 'default'"
          :disabled="props.loading"
          @click="onConfirm"
        >
          <PhSpinnerGap v-if="props.loading" class="h-4 w-4 animate-spin" />
          {{ props.confirmText }}
        </Button>
      </div>
    </div>
  </div>
</template>
