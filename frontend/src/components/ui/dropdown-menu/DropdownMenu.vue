<script setup lang="ts">
/**
 * 下拉菜单组件（设计稿 1.1.2 tb-menu 形态）
 *
 * 职责：通用下拉菜单壳——触发器插槽 + 菜单内容插槽，内置开合状态、
 * Esc 关闭、外点关闭、弹簧入场/退场动画（供 N3 顶栏与 N6~N8 各视图行菜单套用）。
 * 不迁移既有视图内联菜单（documents 的 doc-menu 几何契约由原实现继续承担）。
 *
 * 交互边界：
 * - 外点关闭：open 期间监听 window pointerdown，落点在组件外即关闭；
 * - Esc 关闭：open 期间监听 window keydown Escape；
 * - 触发器经作用域插槽下发 toggle/open，调用方自行绑定（避免包裹层破坏按钮语义）。
 */
import { onBeforeUnmount, ref, watch } from 'vue'

import type { DropdownAlign } from './types'

const props = withDefaults(
  defineProps<{
    /** 菜单对齐：right 右对齐（默认，transform-origin top right，设计稿 tb-menu）/ left 左对齐 */
    align?: DropdownAlign
    /** 菜单最小宽度（px）：设计稿 tb-menu 160px 起；缺省 180 适配中文菜单文案 */
    minWidth?: number
  }>(),
  {
    align: 'right',
    minWidth: 180,
  },
)

const emit = defineEmits<{
  /** 开合状态变化（供调用方联动，如关闭行内其他浮层） */
  'open-change': [open: boolean]
}>()

/** 当前开合状态 */
const open = ref(false)
/** 组件根元素引用（外点判定基准） */
const rootEl = ref<HTMLDivElement | null>(null)

/** 切换开合（触发器点击入口） */
const toggle = () => {
  open.value = !open.value
  emit('open-change', open.value)
}

/** 关闭菜单（供作用域插槽下发，菜单项点击后调用） */
const close = () => {
  open.value = false
  emit('open-change', false)
}

/** 外点关闭：落点不在组件内即关（pointerdown 早于 click，抢在行按钮等外部交互前收起；
 *  落点可能为 window/document 等非 Node 目标——必然在组件外，instanceof 判定防 contains 抛错） */
const onPointerDown = (event: PointerEvent) => {
  const target = event.target
  const inside = target instanceof Node && rootEl.value !== null && rootEl.value.contains(target)
  if (!inside) {
    close()
  }
}

/** Esc 关闭（键盘可达性底线） */
const onKeydown = (event: KeyboardEvent) => {
  if (event.key === 'Escape') {
    close()
  }
}

/** 开合变化同步全局监听挂/卸（避免常驻监听浪费与误关） */
watch(open, (isOpen) => {
  if (isOpen) {
    window.addEventListener('pointerdown', onPointerDown)
    window.addEventListener('keydown', onKeydown)
  } else {
    window.removeEventListener('pointerdown', onPointerDown)
    window.removeEventListener('keydown', onKeydown)
  }
})

// 组件卸载兜底摘除监听（open 态直接卸载的泄漏路径）
onBeforeUnmount(() => {
  window.removeEventListener('pointerdown', onPointerDown)
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <!-- 相对定位壳：菜单以本容器为定位基准 -->
  <div ref="rootEl" class="relative inline-block">
    <!-- 触发器插槽：下发 toggle/open（调用方绑定到自己的按钮/图标上） -->
    <slot name="trigger" :toggle="toggle" :open="open" />
    <!-- 菜单弹层：Enter/Leave 弹簧过渡（设计稿 A9 tb-menu：下移+缩放入场） -->
    <Transition name="dd">
      <div
        v-if="open"
        role="menu"
        class="dd-menu absolute top-full z-50 mt-2 rounded-xl border border-border bg-surface p-1.5 shadow-lg"
        :class="props.align === 'right' ? 'right-0 origin-top-right' : 'left-0 origin-top-left'"
        :style="{ minWidth: `${props.minWidth}px` }"
      >
        <!-- 菜单内容插槽：下发 close（菜单项点击后关闭）；推荐放 DropdownMenuItem -->
        <slot :close="close" />
      </div>
    </Transition>
  </div>
</template>

<style scoped>
/* 入场：弹簧上浮复位（.3s spring，设计稿 A9）；退场：快速下坠收起 */
.dd-enter-active {
  transition:
    opacity 0.3s var(--spring),
    transform 0.3s var(--spring);
}
.dd-leave-active {
  transition:
    opacity 0.2s ease,
    transform 0.2s ease;
}
.dd-enter-from,
.dd-leave-to {
  opacity: 0;
  transform: translateY(10px) scale(0.94);
}
</style>
