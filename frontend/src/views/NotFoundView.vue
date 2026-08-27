<script setup lang="ts">
/**
 * 404 页面（UI 重构 2026-08-25 新增：替代静默重定向仪表盘；
 * 2026-08-27 紫系换肤 N8b 重制空态形态）
 *
 * 未匹配路由统一兜底至此，语义明确的「页面不存在」+ 返回仪表盘。
 * 视觉形态对齐设计稿空态：居中图标圆 + 标题 + 描述 + 动作，v-reveal 入场。
 * 文案契约冻结（404 / 页面不存在或已被移除 / 返回仪表盘），禁止改动。
 */
import { PhGhost } from '@phosphor-icons/vue'
import { useRouter } from 'vue-router'

import { Button } from '@/components/ui/button'

const router = useRouter()
</script>

<template>
  <main
    class="relative flex min-h-screen flex-col items-center justify-center overflow-hidden bg-bg p-6 text-center"
  >
    <!-- 背景装饰光斑：紫系柔光（纯装饰，不参与交互） -->
    <div
      aria-hidden="true"
      class="pointer-events-none absolute -top-32 -left-24 size-96 rounded-full bg-brand-light opacity-80 blur-3xl"
    ></div>
    <div
      aria-hidden="true"
      class="pointer-events-none absolute -bottom-32 -right-24 size-96 rounded-full bg-brand-soft opacity-70 blur-3xl"
    ></div>

    <!-- 空态主体：图标圆 + 标题 + 描述 + 返回动作（滚动入场） -->
    <div v-reveal class="relative flex flex-col items-center">
      <div
        class="grid h-16 w-16 place-items-center rounded-2xl bg-brand-soft ring-8 ring-brand-soft/50"
      >
        <PhGhost class="h-8 w-8 text-brand" weight="duotone" />
      </div>
      <h1 class="mt-6 text-2xl font-bold tracking-tight text-text">404</h1>
      <p class="mt-2 text-sm text-text-muted">页面不存在或已被移除</p>
      <Button class="mt-7" @click="router.push({ name: 'dashboard' })">返回仪表盘</Button>
    </div>
  </main>
</template>
