<script setup lang="ts">
/**
 * 下拉菜单选项组件（设计稿 1.1.2 tb-opt 形态）
 *
 * 职责：DropdownMenu 菜单内容的标准选项——紫系 hover 高亮（brand-soft 底 +
 * brand 字色 + 左移 4px 位移反馈）、danger 色系（删除/踢出等破坏性操作）。
 * 作为 menuitem 渲染（role 由 DropdownMenu 的 role="menu" 容器语义承接）。
 */
const props = withDefaults(
  defineProps<{
    /** 选项文案（用户可见；不允许为空） */
    label: string
    /** 色系：default 常规（紫系 hover）/ danger 破坏性操作（玫红）；缺省 default */
    tone?: 'default' | 'danger'
    /** 禁用态：置灰不可点、不抛 click；缺省 false */
    disabled?: boolean
  }>(),
  {
    tone: 'default',
    disabled: false,
  },
)

const emit = defineEmits<{
  /** 选项被点击（disabled 时不抛出；关闭菜单由调用方经 DropdownMenu 的 close 处理） */
  click: [label: string]
}>()

/** 点击处理：禁用态吞掉事件，正常态透传文案 */
const onClick = () => {
  if (props.disabled) {
    return
  }
  emit('click', props.label)
}
</script>

<template>
  <!-- menuitem 语义 + tb-opt 造型：圆角 8px、13.5px/600，hover 见 scoped 位移+紫底 -->
  <button
    type="button"
    role="menuitem"
    class="dd-item flex w-full items-center gap-2 rounded-lg px-3 py-[9px] text-left text-[13.5px] font-semibold"
    :class="
      props.tone === 'danger'
        ? 'text-danger hover:bg-red-50'
        : 'text-text-muted hover:bg-brand-soft hover:text-brand'
    "
    :disabled="props.disabled"
    @click="onClick"
  >
    <!-- 前置图标插槽（可选，如 Phosphor 操作图标） -->
    <slot name="icon" />
    <span class="flex-1">{{ props.label }}</span>
    <!-- 尾部插槽（可选，如选中勾/快捷键提示） -->
    <slot name="trailing" />
  </button>
</template>

<style scoped>
/* hover 位移反馈：左移 4px（设计稿 tb-opt:hover padding-left 12→16px 的位移语义） */
.dd-item:not(:disabled) {
  transition:
    background-color 0.2s ease,
    color 0.2s ease,
    padding-left 0.2s ease;
  padding-left: 12px;
}
.dd-item:not(:disabled):hover {
  padding-left: 16px;
}
.dd-item:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}
</style>
