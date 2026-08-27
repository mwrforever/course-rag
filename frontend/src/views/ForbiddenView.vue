<script setup lang="ts">
/**
 * 403 无权限页（2026-08-27 紫系换肤 N8b 重制空态形态）
 *
 * 权限路由守卫（meta.roles 不匹配）重定向至此（设计 §3.1/§3.2：B 端无权限页）。
 * 公开路由：提供返回仪表盘出口，不要求登录态（未登录场景同样可读提示）。
 * 视觉形态对齐设计稿空态：居中图标圆 + 标题 + 描述 + 动作，v-reveal 入场。
 * 文案契约冻结：E2E feedback-admin 断言「无权访问」文案在场，禁止改动。
 */
import { useRouter } from 'vue-router'
import { PhLockKey } from '@phosphor-icons/vue'

import { Button } from '@/components/ui/button'

const router = useRouter()
</script>

<template>
  <main
    class="relative flex min-h-screen flex-col items-center justify-center overflow-hidden bg-bg p-6 text-center"
  >
    <!-- 背景装饰光斑：紫系柔光（纯装饰，不参与交互；令牌取色禁止散落硬编码） -->
    <div
      aria-hidden="true"
      class="pointer-events-none absolute -top-32 -right-24 size-96 rounded-full bg-brand-soft opacity-70 blur-3xl"
    ></div>
    <div
      aria-hidden="true"
      class="pointer-events-none absolute -bottom-32 -left-24 size-96 rounded-full bg-brand-light opacity-80 blur-3xl"
    ></div>

    <!-- 空态主体：图标圆 + 标题 + 描述 + 返回动作（滚动入场） -->
    <div v-reveal class="relative flex flex-col items-center">
      <div class="grid h-16 w-16 place-items-center rounded-2xl bg-danger/10 ring-8 ring-danger/5">
        <PhLockKey class="h-8 w-8 text-danger" weight="duotone" />
      </div>
      <h1 class="mt-6 text-2xl font-bold tracking-tight text-text">无权访问</h1>
      <p class="mt-2 max-w-md text-sm leading-relaxed text-text-muted">
        当前账号没有访问该页面的权限，如需开通请联系系统管理员。
      </p>
      <Button class="mt-7" @click="router.push('/dashboard')">返回仪表盘</Button>
    </div>
  </main>
</template>
