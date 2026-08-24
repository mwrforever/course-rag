<script setup lang="ts">
/**
 * 仪表盘页（骨架）
 *
 * Task 15 仅验证 tokens 与基础组件链路可用：KPI 统计卡、快捷入口、
 * 最近文档与反馈趋势图随仪表盘页面任务落地。页头 H1 由布局壳
 * （AdminLayout 顶部 meta.title）统一渲染，本页仅提供内容区。
 */
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
</script>

<template>
  <div class="flex items-center gap-3">
    <Button>刷新</Button>
    <Badge variant="success">INDEXED</Badge>
    <Badge variant="warning">PENDING</Badge>
  </div>
  <p class="mt-4 text-sm text-text-subtle">KPI 统计卡与趋势图随后续任务落地</p>
</template>
