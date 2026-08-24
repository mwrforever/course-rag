<script setup lang="ts">
/**
 * 403 无权限页
 *
 * 权限路由守卫（meta.roles 不匹配）重定向至此（设计 §3.1/§3.2：B 端无权限页）。
 * 公开路由：提供返回仪表盘出口，不要求登录态（未登录场景同样可读提示）。
 */
import { useRouter } from 'vue-router'
import { PhLockKey } from '@phosphor-icons/vue'

import { Button } from '@/components/ui/button'

const router = useRouter()
</script>

<template>
  <main class="flex min-h-screen flex-col items-center justify-center bg-bg p-6 text-center">
    <div class="flex h-14 w-14 items-center justify-center rounded-xl bg-danger/10">
      <PhLockKey class="h-7 w-7 text-danger" weight="duotone" />
    </div>
    <h1 class="mt-5 text-2xl font-bold text-text">无权访问</h1>
    <p class="mt-2 max-w-md text-sm leading-relaxed text-text-muted">
      当前账号没有访问该页面的权限，如需开通请联系系统管理员。
    </p>
    <Button class="mt-6" @click="router.push('/dashboard')">返回仪表盘</Button>
  </main>
</template>
