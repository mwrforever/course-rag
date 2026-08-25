<script setup lang="ts">
/**
 * 登录页：左右分栏（左 40% slate-900 品牌区 + 右表单区，设计 §2.4 /login 行）
 *
 * 交互契约（设计 §2 登录页 + §3.1 认证 + §3.2 错误分级）：
 * - username + 密码登录（无记住我、无注册入口）
 * - zod 前置校验：用户名非空、密码 ≥6 位（校验不过不发请求，字段级就地报错）
 * - 接口错误分级展示：401「用户名或密码错误」/ 403「当前账号无管理后台访问权限」
 *   / 503「服务暂时不可用，请稍后重试」/ 网络错误「网络连接失败，请检查网络」
 * - 登录成功跳 ?redirect= 回跳参数或仪表盘；角色门禁在 auth store 内完成
 *   （STUDENT 等角色登录：提示无权限、不落凭据、停留登录页）
 */
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { z } from 'zod'
import { PhDatabase, PhEye, PhEyeSlash, PhLock, PhSpinnerGap, PhUser } from '@phosphor-icons/vue'

import { Button } from '@/components/ui/button'
import { ApiError } from '@/lib/api'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

/** 登录表单校验 schema（设计 §2：username 非空 + password ≥6 位） */
const loginSchema = z.object({
  username: z.string().min(1, '请输入用户名'),
  password: z.string().min(6, '密码至少 6 位'),
})

const username = ref('')
const password = ref('')
/** 密码可见性切换（眼睛图标，默认隐藏） */
const showPassword = ref(false)
/** 提交中 loading 态（按钮禁用 + spinner，防止重复提交） */
const loading = ref(false)
/** 字段级校验错误（key 与表单字段一一对应） */
const fieldErrors = ref<Partial<Record<'username' | 'password', string>>>({})
/** 接口级错误（顶部 Alert 展示，设计 §3.2 分级文案） */
const errorMessage = ref('')

/**
 * 接口错误分级文案
 *
 * @param err 捕获的异常（ApiError 为业务/网络错误，其余为未知异常）
 * @returns 展示文案：503 统一降级「服务暂时不可用，请稍后重试」；其余透出 ApiError.message
 *          （401 用户名或密码错误 / 403 后端禁用或 store 无权限文案）
 */
function messageOf(err: unknown): string {
  if (err instanceof ApiError) {
    return err.code === 503 ? '服务暂时不可用，请稍后重试' : err.message
  }
  return '登录失败，请稍后重试'
}

/** 表单校验失败：按字段扁平化写入字段级错误，不发请求 */
function applyFieldErrors(err: z.ZodError) {
  fieldErrors.value = {}
  for (const issue of err.issues) {
    const key = issue.path[0]
    if (typeof key === 'string') {
      fieldErrors.value[key as 'username' | 'password'] = issue.message
    }
  }
}

/**
 * 提交登录：zod 前置校验 → store.login（角色门禁在其中）→ 成功跳转 redirect/仪表盘
 *
 * 成功后清除字段错误与接口错误；失败停留登录页展示分级文案。
 */
async function handleSubmit() {
  const parsed = loginSchema.safeParse({ username: username.value, password: password.value })
  if (!parsed.success) {
    applyFieldErrors(parsed.error)
    return
  }
  fieldErrors.value = {}
  errorMessage.value = ''
  loading.value = true
  try {
    await auth.login(username.value, password.value)
    // 跳转回跳参数（登录前被守卫拦截的目标页）或仪表盘
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard'
    await router.push(redirect)
  } catch (err) {
    // 登录失败：分级文案展示，不清空已填表单（便于用户修正重试）
    errorMessage.value = messageOf(err)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="flex min-h-screen bg-bg text-text">
    <!-- 左 40% 品牌区：slate-900 深底 + 几何渐变徽标 + 价值主张（设计 §2.4 登录行） -->
    <aside class="hidden w-[40%] flex-col justify-between bg-slate-900 p-10 text-white md:flex">
      <div class="flex items-center gap-3">
        <div
          class="flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br from-blue-600 to-blue-300"
        >
          <PhDatabase class="h-5 w-5 text-white" weight="bold" />
        </div>
        <span class="text-lg font-semibold tracking-wide">课程助手管理后台</span>
      </div>
      <div>
        <h2 class="text-3xl font-bold leading-snug">知识运维与管理，一处完成</h2>
        <p class="mt-3 max-w-sm text-sm leading-relaxed text-slate-400">
          文档入库、分片修正、课程排期与安全审计，全部管理操作聚合在同一个 cockpit。
        </p>
      </div>
      <p class="text-xs text-slate-400">{{ new Date().getFullYear() }} 课程助手 · B 端管理后台</p>
    </aside>

    <!-- 右表单区：surface 卡片（无记住我、无注册入口，设计 §2 登录页） -->
    <section class="flex flex-1 items-center justify-center p-6">
      <div class="w-full max-w-[400px]">
        <div class="mb-8">
          <h1 class="text-2xl font-bold text-text">欢迎回来</h1>
          <p class="mt-1 text-sm text-text-muted">使用管理账号登录后继续</p>
        </div>

        <!-- 接口错误 Alert（401/403/503/网络，分级文案见 messageOf） -->
        <div
          v-if="errorMessage"
          role="alert"
          class="mb-4 rounded-lg border border-danger/30 bg-red-50 px-4 py-3 text-sm text-danger"
        >
          {{ errorMessage }}
        </div>

        <form class="space-y-4" novalidate @submit.prevent="handleSubmit">
          <div>
            <label for="username" class="mb-1.5 block text-sm font-medium text-text">用户名</label>
            <div class="relative">
              <PhUser
                class="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-text-subtle"
              />
              <input
                id="username"
                v-model="username"
                type="text"
                autocomplete="username"
                aria-label="用户名"
                class="h-10 w-full rounded-lg border border-border bg-surface pl-9 pr-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
                placeholder="请输入用户名"
              />
            </div>
            <p v-if="fieldErrors.username" class="mt-1 text-xs text-danger">
              {{ fieldErrors.username }}
            </p>
          </div>

          <div>
            <label for="password" class="mb-1.5 block text-sm font-medium text-text">密码</label>
            <div class="relative">
              <PhLock
                class="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-text-subtle"
              />
              <input
                id="password"
                v-model="password"
                :type="showPassword ? 'text' : 'password'"
                autocomplete="current-password"
                aria-label="密码"
                class="h-10 w-full rounded-lg border border-border bg-surface pl-9 pr-10 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
                placeholder="请输入密码"
              />
              <button
                type="button"
                :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                class="absolute right-2 top-1/2 -translate-y-1/2 rounded p-1.5 text-text-subtle transition-colors duration-150 hover:text-text"
                @click="showPassword = !showPassword"
              >
                <PhEyeSlash v-if="showPassword" class="h-4 w-4" />
                <PhEye v-else class="h-4 w-4" />
              </button>
            </div>
            <p v-if="fieldErrors.password" class="mt-1 text-xs text-danger">
              {{ fieldErrors.password }}
            </p>
          </div>

          <Button type="submit" class="w-full" :disabled="loading">
            <PhSpinnerGap v-if="loading" class="h-4 w-4 animate-spin" />
            {{ loading ? '登录中' : '登 录' }}
          </Button>
        </form>

        <p class="mt-6 text-center text-xs text-text-subtle">
          忘记密码或无法登录？请联系系统管理员
        </p>
      </div>
    </section>
  </main>
</template>
