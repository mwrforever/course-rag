<script setup lang="ts">
/**
 * 轻量样式化数据表（设计稿 1.1.2 Best Selling Courses 表格形态）
 *
 * 职责：只承担表格视觉壳——lav 圆角表头 / 行悬停高亮 / 行级联入场，
 * 数据与单元格渲染完全由调用方经插槽提供（header 插槽放 th、默认插槽放 tr）。
 * 项目现状：各视图各自内联 table（doc-table/student-table 等 testid 契约密集），
 * 本组件供 N6~N8 视图重绘时套用，不迁移既有视图。
 *
 * 行入场机制（设计稿 A20）：挂载后给 tbody 每行按序写 --d（0.15s 步进，封顶 0.9s），
 * 表格进入视口（IO threshold .15）后整体加 .dt-in 过渡到可见；
 * 偏好减少动效或环境无 IO 时不武装隐藏态（行直接可见，无障碍底线）。
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'

import { prefersReducedMotion } from '@/lib/motion'

const props = withDefaults(
  defineProps<{
    /** 表格可访问名称（aria-label，读屏用途）；缺省不输出该属性 */
    label?: string
  }>(),
  {
    label: '',
  },
)

/** 表格根元素引用（行入场编排的观察目标） */
const rootEl = ref<HTMLTableElement | null>(null)
/** 行入场观察器（unmount 前断开） */
let observer: IntersectionObserver | null = null

onMounted(() => {
  const table = rootEl.value
  if (!table) {
    return
  }
  // 降级：偏好减少动效或环境无 IO → 不武装隐藏态，行直接可见
  if (prefersReducedMotion() || typeof IntersectionObserver === 'undefined') {
    return
  }
  // 级联延迟：每行 0.15s 步进、封顶 0.9s（设计稿 A20 --d=.15*i；长表防尾部延迟过大；
  // 毫秒整数运算后换算秒，规避 0.15 浮点累加误差）
  table.querySelectorAll<HTMLTableRowElement>('tbody tr').forEach((tr, index) => {
    tr.style.setProperty('--d', `${Math.min(index * 150, 900) / 1000}s`)
  })
  // 武装隐藏态（.dt-armed 下行 opacity:0，见 scoped 样式），IO 命中后 .dt-in 整体点亮
  table.classList.add('dt-armed')
  observer = new IntersectionObserver(
    (entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        table.classList.add('dt-in')
        observer?.disconnect()
        observer = null
      }
    },
    { threshold: 0.15 },
  )
  observer.observe(table)
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
})
</script>

<template>
  <!-- 表格壳：全宽紧凑文本（数据密度对齐既有视图 text-sm）；样式细节见 scoped -->
  <table ref="rootEl" class="w-full border-collapse text-sm" :aria-label="props.label || undefined">
    <thead>
      <slot name="header" />
    </thead>
    <tbody>
      <slot />
    </tbody>
  </table>
</template>

<style scoped>
/* 表头：lav 底（设计稿 #f3f1fc 归拢 surface-2 令牌）、首末列 10px 圆角（设计稿 thead th）；
   纵距 11px 对齐管理后台密度 7 基线（契约 G.2.1 行高紧凑一致） */
thead :deep(th) {
  padding: 11px 16px;
  text-align: left;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--color-text-muted);
  background: var(--color-surface-2);
}
thead :deep(th:first-child) {
  border-radius: 10px 0 0 10px;
  padding-left: 22px;
}
thead :deep(th:last-child) {
  border-radius: 0 10px 10px 0;
}

/* 单元格：紫白细分隔线（设计稿 #f2f1f8 归拢 border 令牌）+ 行悬停整行浅紫高亮（lav）；
   纵距 17px→12px：密度 7 紧凑基线（全站表格统一，hover 250ms→200ms 对齐 150-300ms 反馈窗口） */
tbody :deep(tr) td {
  padding: 12px 16px;
  font-size: 14px;
  color: var(--color-text-muted);
  border-bottom: 1px solid var(--color-border);
  transition: background 0.2s ease;
}
tbody :deep(tr) td:first-child {
  padding-left: 22px;
}
tbody :deep(tr):hover td {
  background: var(--color-brand-light);
}

/* 行级联入场：仅武装态隐藏（dt-armed），IO 命中后 dt-in 逐行点亮（--d 由 onMounted 写入） */
table.dt-armed tbody :deep(tr) {
  opacity: 0;
  transition: opacity 0.6s ease var(--d, 0s);
}
table.dt-in tbody :deep(tr) {
  opacity: 1;
}

/* 动效降级：偏好减少动效时即便武装态也直接可见（与指令侧降级双保险） */
@media (prefers-reduced-motion: reduce) {
  table.dt-armed tbody :deep(tr) {
    opacity: 1;
    transition: none;
  }
}
</style>
