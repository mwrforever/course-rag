<script setup lang="ts">
/**
 * 404 页面（UI 重构 2026-08-25 新增：替代静默重定向仪表盘）
 *
 * 未匹配路由统一兜底至此，语义明确的「页面不存在」+ 返回仪表盘。
 */
import { PhWarningCircle } from '@phosphor-icons/vue'
import { useRouter } from 'vue-router'

import { Button } from '@/components/ui/button'

const router = useRouter()
</script>

<template>
  <div class="flex min-h-[70vh] flex-col items-center justify-center text-center">
    <div class="flex size-14 items-center justify-center rounded-2xl bg-brand-soft">
      <PhWarningCircle class="size-7 text-brand" />
    </div>
    <h1 class="mt-4 text-2xl font-bold text-text">404</h1>
    <p class="mt-2 text-sm text-text-muted">页面不存在或已被移除</p>
    <Button class="mt-6" @click="router.push({ name: 'dashboard' })">返回仪表盘</Button>
  </div>
</template>
