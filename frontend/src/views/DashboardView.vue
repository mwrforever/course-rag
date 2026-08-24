<script setup lang="ts">
/**
 * 仪表盘页（骨架）
 *
 * Task 15 仅验证 tokens 与基础组件链路可用：KPI 统计卡、快捷入口、
 * 最近文档与反馈趋势图随 Task 16（仪表盘页面）落地。
 */
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
</script>

<template>
  <main class="mx-auto max-w-[1400px] px-8 py-6">
    <header class="mb-6">
      <h1 class="text-2xl font-bold text-text">仪表盘</h1>
      <p class="mt-1 text-sm text-text-muted">知识库运维总览</p>
    </header>
    <div class="flex items-center gap-3">
      <Button>刷新</Button>
      <Badge variant="success">INDEXED</Badge>
      <Badge variant="warning">PENDING</Badge>
    </div>
    <p class="mt-4 text-sm text-text-subtle">KPI 统计卡与趋势图随 Task 16 落地</p>
  </main>
</template>
