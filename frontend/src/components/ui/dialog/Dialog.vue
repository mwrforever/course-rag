<script setup lang="ts">
/**
 * 通用弹窗壳组件（契约 T2.1：统一遮罩形态）
 *
 * 职责：B 端弹窗的标准壳——紫黑遮罩 + 16px 圆角卡 + 菜单级入场动画 + 标题/描述行。
 * 与 ConfirmDialog 的分工：ConfirmDialog 承载「危险/重要操作二次确认」（标题 + 描述 + 取消/确认），
 * 本组件承载「任意内容弹窗」（默认插槽放表单/列表等主体，footer 插槽放动作行），按场景选用。
 * 交互：Esc 与遮罩点击触发关闭（经 can-close 守卫——提交期间可拦截防误关）。
 * 无自身业务状态，无线程安全诉求。
 */
import { onMounted, onUnmounted } from 'vue'

const props = withDefaults(
  defineProps<{
    /** 受控开合（v-model:open）：false 时不渲染任何 DOM */
    open: boolean
    /** 弹窗标题（用户可见，不允许为空） */
    title: string
    /** 描述文案（辅助说明；传空串/缺省时不渲染该行） */
    description?: string
    /** 弹窗卡最大宽度（Tailwind max-w-* 类，缺省 max-w-[480px]） */
    maxWidthClass?: string
    /** 是否允许关闭（Esc/遮罩点击回抛；提交期间置 false 拦截误关，缺省 true） */
    canClose?: boolean
  }>(),
  {
    description: '',
    maxWidthClass: 'max-w-[480px]',
    canClose: true,
  },
)

const emit = defineEmits<{
  /** 开合状态变化（v-model:open；关闭请求经 canClose 守卫后回抛 false） */
  'update:open': [open: boolean]
  /** 关闭请求（Esc / 遮罩点击；供调用方挂额外清理逻辑），携带关闭来源 */
  close: [reason: 'esc' | 'overlay']
}>()

/**
 * 请求关闭：canClose 守卫（提交期间拦截）→ 回抛 update:open=false + close
 *
 * @param reason 关闭来源（esc/overlay，仅日志语义，不影响行为）
 */
function requestClose(reason: 'esc' | 'overlay') {
  if (!props.canClose) return
  emit('update:open', false)
  emit('close', reason)
}

/** 全局 Esc 监听：弹窗开合期间挂载/卸载（遮罩点击由模板 @click.self 承载） */
function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && props.open) {
    event.stopPropagation()
    requestClose('esc')
  }
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onUnmounted(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <!-- 遮罩层：紫黑半透明（overlay 令牌）+ 居中布局；Esc/遮罩自点 = 关闭请求 -->
  <div
    v-if="props.open"
    class="fixed inset-0 z-50 flex animate-fade-in items-center justify-center bg-overlay p-4"
    role="dialog"
    aria-modal="true"
    :aria-label="props.title"
    @click.self="requestClose('overlay')"
  >
    <!-- 弹窗卡：16px 圆角 + 阴影 + 菜单级入场动画；主体 max-h 收纳滚动 -->
    <div
      class="animate-menu-in flex max-h-[85vh] w-full flex-col overflow-y-auto rounded-2xl bg-surface p-6 shadow-lg"
      :class="props.maxWidthClass"
    >
      <h2 class="text-lg font-extrabold tracking-tight text-text">{{ props.title }}</h2>
      <p v-if="props.description" class="mt-2 text-sm leading-relaxed text-text-muted">
        {{ props.description }}
      </p>
      <!-- 主体插槽：表单/列表等弹窗内容 -->
      <slot />
      <!-- 动作区插槽：默认右对齐（取消/确认按钮行） -->
      <div v-if="$slots.footer" class="mt-5 flex justify-end gap-2">
        <slot name="footer" />
      </div>
    </div>
  </div>
</template>
