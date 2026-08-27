<script setup lang="ts">
/**
 * 登录页：左怪物栏 + 右轨道表单双区（2026-08-27 紫系换肤 N4，设计稿 code(14).html 复刻）
 *
 * 交互契约（换肤铁律：认证行为与重构前完全等价）：
 * - username + 密码登录（无记住我、无注册入口、无 demo 账号提示）
 * - zod 前置校验：用户名非空、密码 ≥6 位（校验不过不发请求，字段级就地报错 + shake）
 * - 接口错误分级展示：401「用户名或密码错误」/ 403「当前账号无管理后台访问权限」
 *   / 503「服务暂时不可用，请稍后重试」/ 网络错误「网络连接失败，请检查网络」
 * - 登录成功：按钮 done 态 + 圆环描线 overlay（压缩至 1s，映射报告 3.5：<1.5s 勿拖慢登录）
 *   后跳 ?redirect= 回跳参数或仪表盘；角色门禁在 auth store 内完成（STUDENT 等角色
 *   登录：提示无权限、不落凭据、停留登录页）
 *
 * 换肤新增纯视觉层（不动认证流）：
 * - 四小怪引擎（idle 跟随 / typing 探头 / peek 偷看 / 随机眨眼）——composables/use-monsters
 * - 轨道三层视差（pointermove，偏好减少动效时不挂监听）
 * - 浮动标签 / Caps Lock 提示 / 密码眼睛切换（联动怪物 peek）/ 成功 overlay 圆环双段描线
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { z } from 'zod'
import {
  PhArrowRight,
  PhBookOpen,
  PhChartLineUp,
  PhDatabase,
  PhEye,
  PhEyeSlash,
  PhLock,
  PhUser,
  PhUsers,
} from '@phosphor-icons/vue'

import { useMonsters } from '@/composables/use-monsters'
import { ApiError } from '@/lib/api'
import { prefersReducedMotion } from '@/lib/motion'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

/** 登录表单校验 schema（设计 §2：username 非空 + password ≥6 位） */
const loginSchema = z.object({
  username: z.string().min(1, '请输入用户名'),
  password: z.string().min(6, '密码至少 6 位'),
})

/** 登录成功 overlay 演出时长（毫秒）：圆环描线 + 进度条完成即跳转（压缩设计稿 2.9s → 1s） */
const SUCCESS_REDIRECT_DELAY_MS = 1000

const username = ref('')
const password = ref('')
/** 密码可见性切换（眼睛图标，默认隐藏；明文时四怪偷看） */
const showPassword = ref(false)
/** 提交中 loading 态（按钮禁用 + spinner，防止重复提交） */
const loading = ref(false)
/** 登录成功（按钮 done 态 + overlay 圆环演出开关） */
const success = ref(false)
/** 字段级校验错误（key 与表单字段一一对应；输入即清除本字段错误） */
const fieldErrors = ref<Partial<Record<'username' | 'password', string>>>({})
/** 接口级错误（表单上方 Alert 展示，设计 §3.2 分级文案） */
const errorMessage = ref('')
/** Caps Lock 大写锁定提示（密码框 keydown/keyup 探测，blur 收起） */
const capsOn = ref(false)
/** 校验失败次数计数：驱动字段容器 key 重建以重放 shake 动画（设计稿 B19 重触发语义） */
const shakeTick = ref(0)

/** 轨道视差容器（三层 data-plx-depth 装饰层的父级） */
const orbitWrap = ref<HTMLElement | null>(null)

/** 四小怪引擎（stageRef 绑怪物舞台容器；typing/peek 态由表单事件驱动） */
const { stageRef: monstersStage, setTyping, setPeeking } = useMonsters()

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
 * 密码可见性切换：明文时触发四怪 peek 偷看态（优先于 typing 态）
 */
function togglePassword() {
  showPassword.value = !showPassword.value
  setPeeking(showPassword.value)
}

/**
 * 密码框按键：同步 Caps Lock 状态（getModifierState 探测，不支持环境静默收起）
 */
function syncCapsState(e: KeyboardEvent) {
  capsOn.value = typeof e.getModifierState === 'function' && e.getModifierState('CapsLock')
}

/**
 * 提交登录：zod 前置校验 → store.login（角色门禁在其中）→ 成功 overlay → 跳转 redirect/仪表盘
 *
 * 成功后清除字段错误与接口错误；失败停留登录页展示分级文案（不清空已填表单便于修正重试）。
 */
async function handleSubmit() {
  const parsed = loginSchema.safeParse({ username: username.value, password: password.value })
  if (!parsed.success) {
    applyFieldErrors(parsed.error)
    // 失败计数 +1：字段容器按 key 重建，shake 动画按设计稿 B19 重触发
    shakeTick.value += 1
    return
  }
  fieldErrors.value = {}
  errorMessage.value = ''
  loading.value = true
  try {
    await auth.login(username.value, password.value)
    // 成功：按钮切 done 态并 overlay 圆环描线演出，压缩演出后跳转
    loading.value = false
    success.value = true
    await new Promise((resolve) => setTimeout(resolve, SUCCESS_REDIRECT_DELAY_MS))
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

/**
 * 轨道视差：鼠标位置 → 三层装饰反向位移（depth 8/14/20，设计稿 B9）
 */
function applyParallax(e: PointerEvent) {
  const wrap = orbitWrap.value
  if (!wrap || typeof window === 'undefined') {
    return
  }
  const px = e.clientX / window.innerWidth - 0.5
  const py = e.clientY / window.innerHeight - 0.5
  for (const el of wrap.querySelectorAll<HTMLElement>('[data-plx-depth]')) {
    const depth = Number(el.dataset.plxDepth) || 10
    el.style.translate = `${-px * depth * 2}px ${-py * depth * 2}px`
  }
}

onMounted(() => {
  // 偏好减少动效：不挂视差监听（怪物引擎降级在 use-monsters 内部处理）
  if (!prefersReducedMotion()) {
    window.addEventListener('pointermove', applyParallax)
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('pointermove', applyParallax)
})
</script>

<template>
  <main class="login-page relative min-h-screen overflow-x-hidden bg-bg text-text">
    <!-- 环境光斑滤镜定义（怪物投影 feGaussianBlur 共用，零占位） -->
    <svg aria-hidden="true" class="absolute h-0 w-0">
      <defs>
        <filter id="login-soft-blur" x="-60%" y="-60%" width="220%" height="220%">
          <feGaussianBlur stdDeviation="4" />
        </filter>
      </defs>
    </svg>

    <!-- 右侧背景光斑（fixed 装饰层，drift 17s/20s 反向漂浮） -->
    <div
      aria-hidden="true"
      class="pointer-events-none fixed inset-0 z-0 overflow-hidden opacity-60"
    >
      <span class="login-orb login-orb-a" />
      <span class="login-orb login-orb-b" />
    </div>

    <!-- 底部版权（桌面端居中固定；≤980px 隐藏） -->
    <p class="login-copyright">{{ new Date().getFullYear() }} 课程助手 · B 端管理后台</p>

    <div class="login-layout relative z-[1] grid min-h-screen grid-cols-1">
      <!-- ============ 左栏：深灰渐变 + 品牌区 + 四小怪 ============ -->
      <aside class="login-chars-side relative flex flex-col items-center overflow-hidden">
        <span aria-hidden="true" class="login-env-light login-env-light-a" />
        <span aria-hidden="true" class="login-env-light login-env-light-b" />

        <!-- 品牌行：白色书本 logo + 品牌名 + Admin 玻璃标签 -->
        <div class="login-brand-row relative z-[3] flex items-center gap-3 self-start">
          <svg class="login-brand-logo h-10 w-10" viewBox="0 0 48 48" aria-hidden="true">
            <path
              class="login-mist-fill"
              fill-opacity=".28"
              d="M24 10.6C20.7 7.9 16.2 6.7 10 6.7c-1 0-1.9.8-1.9 1.9v22.9c0 1 .9 1.9 1.9 1.9 5.5 0 9.6 1.1 12.6 3.4.8.6 1.4.6 2.2 0 3-2.3 7.1-3.4 12.6-3.4 1 0 1.9-.9 1.9-1.9V8.6c0-1.1-.9-1.9-1.9-1.9-6.2 0-10.7 1.2-14 3.9z"
            />
            <path
              class="login-brand-accent-fill"
              d="M24 13.4c-2.7-2-6.2-3-10.6-3.1v18c4.1.2 7.6 1.1 10.6 2.9 3-1.8 6.5-2.7 10.6-2.9v-18c-4.4.1-7.9 1.1-10.6 3.1z"
            />
            <path
              class="login-mist-stroke"
              stroke-width="1.6"
              stroke-linecap="round"
              fill="none"
              d="M16.5 15h5M16.5 18.6h5M26.5 15h5M26.5 18.6h5"
            />
          </svg>
          <span class="text-[21px] font-extrabold tracking-tight text-white">课程助手管理后台</span>
          <span class="login-brand-tag">Admin</span>
        </div>

        <!-- 价值主张：渐变标语 + 小怪玩法引导 -->
        <div class="login-side-copy relative z-[2] text-center">
          <h2
            class="text-[clamp(24px,2.4vw,32px)] font-extrabold leading-snug tracking-tight text-white"
          >
            知识运维与管理，<span class="login-grad-text">一处完成</span>
          </h2>
          <p class="login-copy-lead mt-3 max-w-[340px] text-sm leading-relaxed text-white/70">
            四只好奇的小怪守在控制台旁——输入密码时，试试能不能阻止它们偷看。
          </p>
          <p class="login-copy-lead mt-2 max-w-[340px] text-sm leading-relaxed text-white/70">
            文档入库、分片修正、课程排期与安全审计，聚合在同一个控制台。
          </p>
        </div>

        <!-- 怪物舞台：紫高个 / 黑中个 / 黄圆顶 / 橙拱形（引擎挂载点） -->
        <div ref="monstersStage" class="login-monsters-stage relative z-[2] mt-auto w-full">
          <div class="login-monsters relative h-[clamp(200px,32vmin,300px)]">
            <!-- 紫色高个（眼睛位于上部 1/4 处） -->
            <svg
              data-monster="purple"
              class="login-monster is-purple"
              viewBox="0 0 200 300"
              aria-hidden="true"
            >
              <ellipse class="monster-shadow" cx="100" cy="294" rx="62" ry="7" />
              <g data-lean class="monster-lean" style="--lo: 100px 292px">
                <rect class="monster-body" x="6" y="6" width="188" height="286" rx="42" />
                <ellipse class="monster-sheen" cx="62" cy="52" rx="34" ry="24" fill-opacity=".08" />
                <g data-face class="monster-face">
                  <g class="monster-eye">
                    <circle class="monster-ball" cx="72" cy="88" r="20" />
                    <g data-pupil-track class="monster-pupil-track">
                      <circle data-pupil class="monster-pupil" cx="72" cy="88" r="9" />
                      <circle class="monster-glint" cx="69.2" cy="85.2" r="2.6" />
                    </g>
                  </g>
                  <g class="monster-eye">
                    <circle class="monster-ball" cx="128" cy="88" r="20" />
                    <g data-pupil-track class="monster-pupil-track">
                      <circle data-pupil class="monster-pupil" cx="128" cy="88" r="9" />
                      <circle class="monster-glint" cx="125.2" cy="85.2" r="2.6" />
                    </g>
                  </g>
                </g>
              </g>
            </svg>

            <!-- 黑色中个 -->
            <svg
              data-monster="black"
              class="login-monster is-black"
              viewBox="0 0 170 252"
              aria-hidden="true"
            >
              <ellipse class="monster-shadow" cx="85" cy="247" rx="54" ry="6.5" />
              <g data-lean class="monster-lean" style="--lo: 85px 245px">
                <rect class="monster-body" x="5" y="6" width="160" height="240" rx="36" />
                <ellipse class="monster-sheen" cx="52" cy="46" rx="28" ry="20" fill-opacity=".07" />
                <g data-face class="monster-face">
                  <g class="monster-eye">
                    <circle class="monster-ball" cx="61" cy="84" r="17.5" />
                    <g data-pupil-track class="monster-pupil-track">
                      <circle data-pupil class="monster-pupil" cx="61" cy="84" r="8" />
                      <circle class="monster-glint" cx="58.6" cy="81.6" r="2.3" />
                    </g>
                  </g>
                  <g class="monster-eye">
                    <circle class="monster-ball" cx="109" cy="84" r="17.5" />
                    <g data-pupil-track class="monster-pupil-track">
                      <circle data-pupil class="monster-pupil" cx="109" cy="84" r="8" />
                      <circle class="monster-glint" cx="106.6" cy="81.6" r="2.3" />
                    </g>
                  </g>
                </g>
              </g>
            </svg>

            <!-- 黄色圆顶（黑点眼 + 横线嘴） -->
            <svg
              data-monster="yellow"
              class="login-monster is-yellow"
              viewBox="0 0 180 240"
              aria-hidden="true"
            >
              <ellipse class="monster-shadow" cx="90" cy="235" rx="56" ry="6.5" />
              <g data-lean class="monster-lean" style="--lo: 90px 233px">
                <path
                  class="monster-body"
                  d="M12 234 L12 120 Q12 18 90 18 Q168 18 168 120 L168 234 Z"
                />
                <ellipse class="monster-sheen" cx="56" cy="60" rx="28" ry="22" fill-opacity=".22" />
                <g data-face class="monster-face">
                  <g class="monster-eye">
                    <g data-pupil-track class="monster-pupil-track">
                      <circle data-pupil class="monster-pupil" cx="66" cy="104" r="6.5" />
                    </g>
                  </g>
                  <g class="monster-eye">
                    <g data-pupil-track class="monster-pupil-track">
                      <circle data-pupil class="monster-pupil" cx="114" cy="104" r="6.5" />
                    </g>
                  </g>
                  <path class="monster-mouth" d="M56 150 L124 150" />
                </g>
              </g>
            </svg>

            <!-- 橙色拱形矮个（最前层） -->
            <svg
              data-monster="orange"
              class="login-monster is-orange"
              viewBox="0 0 260 200"
              aria-hidden="true"
            >
              <ellipse class="monster-shadow" cx="130" cy="196" rx="80" ry="7" />
              <g data-lean class="monster-lean" style="--lo: 130px 194px">
                <path
                  class="monster-body"
                  d="M12 194 L12 106 Q12 12 130 12 Q248 12 248 106 L248 194 Z"
                />
                <ellipse class="monster-sheen" cx="74" cy="58" rx="38" ry="26" fill-opacity=".14" />
                <g data-face class="monster-face">
                  <g class="monster-eye">
                    <g data-pupil-track class="monster-pupil-track">
                      <circle data-pupil class="monster-pupil" cx="96" cy="102" r="6" />
                    </g>
                  </g>
                  <g class="monster-eye">
                    <g data-pupil-track class="monster-pupil-track">
                      <circle data-pupil class="monster-pupil" cx="164" cy="102" r="6" />
                    </g>
                  </g>
                </g>
              </g>
            </svg>
          </div>
        </div>
      </aside>

      <!-- ============ 右栏：轨道装饰 + 登录表单 ============ -->
      <section class="login-stage relative grid min-h-screen place-items-center">
        <!-- 三层视差轨道（纯装饰）：外环文字 / 中环虚线 + 功能浮标 / 内环彗星 -->
        <div ref="orbitWrap" aria-hidden="true" class="login-orbit-wrap">
          <!-- 外环：环形旋转文字 + 四枚脉冲点（depth 8） -->
          <div class="login-plx" data-plx-depth="8">
            <div class="login-orbit-in" style="--d: 0.05s">
              <div class="login-orbit-ring is-outer">
                <div class="login-circ-rotor">
                  <svg class="login-circ-text" viewBox="0 0 1000 1000">
                    <defs>
                      <path
                        id="login-orbit-text-path"
                        d="M500,500 m-468,0 a468,468 0 1,1 936,0 a468,468 0 1,1 -936,0"
                      />
                    </defs>
                    <text class="login-circ-copy" font-size="27" letter-spacing="5">
                      <textPath
                        href="#login-orbit-text-path"
                        textLength="2941"
                        lengthAdjust="spacingAndGlyphs"
                      >
                        COMMERCE RAG ✦ ADMIN CONSOLE ✦ KNOWLEDGE OPS ✦ COURSE MANAGEMENT ✦ SECURE
                        &amp; SCALABLE ✦
                      </textPath>
                    </text>
                  </svg>
                </div>
                <span class="login-m-dot" style="left: 50%; top: 0" />
                <span class="login-m-dot" style="left: 100%; top: 50%; --d: 0.7s" />
                <span class="login-m-dot" style="left: 50%; top: 100%; --d: 1.4s" />
                <span class="login-m-dot" style="left: 0; top: 50%; --d: 2.1s" />
              </div>
            </div>
          </div>

          <!-- 中环：虚线旋转 + 四枚功能浮标（真实功能文案，无统计假数字；depth 14） -->
          <div class="login-plx" data-plx-depth="14">
            <div class="login-orbit-in" style="--d: 0.18s">
              <div class="login-orbit-ring is-mid" />
            </div>
            <div class="login-onode" style="left: 75.8%; top: 24.2%; --d: 0.9s">
              <div class="login-pill" style="--fd: 6s">
                <span class="login-pill-ic c1"
                  ><PhDatabase class="h-[17px] w-[17px]" weight="bold"
                /></span>
                <div><b>知识库管理</b><span>多模态文档入库与分片修正</span></div>
              </div>
            </div>
            <div class="login-onode" style="left: 24.2%; top: 24.2%; --d: 1.05s">
              <div class="login-pill" style="--fd: 7s; --fo: 0.8s">
                <span class="login-pill-ic c2"
                  ><PhBookOpen class="h-[17px] w-[17px]" weight="bold"
                /></span>
                <div><b>课程管理</b><span>课程排期与内容组织</span></div>
              </div>
            </div>
            <div class="login-onode" style="left: 24.2%; top: 75.8%; --d: 1.2s">
              <div class="login-pill" style="--fd: 6.4s; --fo: 1.6s">
                <span class="login-pill-ic c3"
                  ><PhUsers class="h-[17px] w-[17px]" weight="bold"
                /></span>
                <div><b>学生管理</b><span>角色账号与状态维护</span></div>
              </div>
            </div>
            <div class="login-onode" style="left: 75.8%; top: 75.8%; --d: 1.35s">
              <div class="login-pill" style="--fd: 7.6s; --fo: 0.4s">
                <span class="login-pill-ic c4">
                  <PhChartLineUp class="h-[17px] w-[17px]" weight="bold" />
                </span>
                <div><b>反馈报表</b><span>意图统计与赞踩回溯</span></div>
              </div>
            </div>
          </div>

          <!-- 内环：反向虚线 + 彗星（depth 20） -->
          <div class="login-plx" data-plx-depth="20">
            <div class="login-orbit-in" style="--d: 0.3s">
              <div class="login-orbit-ring is-inner">
                <span class="login-comet"><i /></span>
              </div>
            </div>
          </div>
        </div>

        <!-- 表单层：紫色光晕氛围 + 地面光斑 + 居中登录卡 -->
        <div class="login-form-zone absolute inset-0 z-[5] grid place-items-center">
          <div aria-hidden="true" class="login-aura" />
          <div class="login-col relative w-[min(400px,92vw)]">
            <!-- 标题：逐词上浮入场 -->
            <div class="login-head mb-6 text-center">
              <h1 class="text-[27px] font-extrabold tracking-tight">
                <span class="login-word"><i style="--d: 0.6s">欢迎</i></span
                ><span class="login-word"><i style="--d: 0.68s">回来</i></span>
              </h1>
              <p class="mt-1.5 text-sm font-medium text-text-muted">使用管理账号登录后继续</p>
            </div>

            <!-- 接口错误 Alert（401/403/503/网络，分级文案见 messageOf） -->
            <div
              v-if="errorMessage"
              role="alert"
              class="mb-4 rounded-xl border border-danger/30 bg-red-50 px-4 py-3 text-sm font-semibold text-danger"
            >
              {{ errorMessage }}
            </div>

            <form class="space-y-4" novalidate @submit.prevent="handleSubmit">
              <!-- 用户名：浮动标签 + 左图标 + 错误 shake（key 重建重放动画） -->
              <div
                :key="`username-${shakeTick}`"
                class="login-field"
                :class="{ 'is-error': !!fieldErrors.username }"
                style="--d: 0.7s"
              >
                <input
                  id="username"
                  v-model="username"
                  type="text"
                  autocomplete="username"
                  aria-label="用户名"
                  placeholder=" "
                  class="login-input"
                  @focus="setTyping(true)"
                  @blur="setTyping(false)"
                  @input="fieldErrors.username = ''"
                />
                <PhUser aria-hidden="true" class="login-f-ic" />
                <label for="username" class="login-label">用户名</label>
                <p class="login-err-msg" :class="{ show: !!fieldErrors.username }">
                  {{ fieldErrors.username ?? '' }}
                </p>
              </div>

              <!-- 密码：浮动标签 + 眼睛切换（联动怪物 peek）+ Caps Lock 提示 -->
              <div
                :key="`password-${shakeTick}`"
                class="login-field"
                :class="{ 'is-error': !!fieldErrors.password }"
                style="--d: 0.8s"
              >
                <input
                  id="password"
                  v-model="password"
                  :type="showPassword ? 'text' : 'password'"
                  autocomplete="current-password"
                  aria-label="密码"
                  placeholder=" "
                  class="login-input"
                  @keydown="syncCapsState"
                  @keyup="syncCapsState"
                  @blur="capsOn = false"
                  @input="fieldErrors.password = ''"
                />
                <PhLock aria-hidden="true" class="login-f-ic" />
                <label for="password" class="login-label">密码</label>
                <button
                  type="button"
                  class="login-eye"
                  :class="{ show: showPassword }"
                  :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                  @click="togglePassword"
                >
                  <PhEye class="ic-on" />
                  <PhEyeSlash class="ic-off" />
                </button>
                <div aria-hidden="true" class="login-caps" :class="{ show: capsOn }">
                  <i />大写锁定已开启
                </div>
                <p class="login-err-msg" :class="{ show: !!fieldErrors.password }">
                  {{ fieldErrors.password ?? '' }}
                </p>
              </div>

              <!-- 提交按钮：渐变 + shine 扫光 / loading spinner / 成功 done 三态 -->
              <button
                type="submit"
                class="login-submit"
                :class="{ 'is-loading': loading, 'is-done': success }"
                :disabled="loading"
              >
                <span class="txt">{{ loading ? '登录中' : success ? '登录成功' : '登 录' }}</span>
                <PhArrowRight aria-hidden="true" class="arr" />
                <span aria-hidden="true" class="spinner" />
              </button>
            </form>

            <p class="login-hint mt-5 text-center text-xs font-semibold text-text-subtle">
              忘记密码或无法登录？请联系系统管理员
            </p>

            <!-- 地面光斑 + 中线闪光（表单底部氛围） -->
            <div aria-hidden="true" class="login-ground" />
          </div>
        </div>
      </section>
    </div>

    <!-- 登录成功 overlay：圆环双段描线 + 进度条，演出结束即完成跳转 -->
    <div v-if="success" class="login-success-overlay" role="status" aria-live="polite">
      <svg class="login-suc-ring" viewBox="0 0 100 100" aria-hidden="true">
        <circle cx="50" cy="50" r="46" style="--dash-from: 289" />
        <path d="M30 51l14 14 27-28" style="--dash-from: 60" />
      </svg>
      <div class="login-suc-title">登录成功</div>
      <div class="login-suc-sub">正在进入管理后台…</div>
      <div class="login-suc-bar"><i /></div>
    </div>
  </main>
</template>

<style scoped>
/* ==================================================================
   页面局部装饰色（单一来源集中声明）
   main.css @theme 已冻结：设计稿专属、无语义令牌对应的色值统一收敛在此，
   不散落在任何工具类/行内样式中；凡与令牌同值的一律引用令牌或 color-mix 派生。
   ================================================================== */
.login-page {
  /* 左栏深灰渐变（设计稿 165deg 三段） */
  --lg-char-grad: linear-gradient(165deg, #a6a9b1 0%, #83868f 46%, #5e6169 100%);
  /* 怪物主色（紫/黑/黄/橙）与瞳孔墨色 */
  --lg-mon-purple: #5b2ee5;
  --lg-mon-black: #1e1e23;
  --lg-mon-yellow: #f2e33d;
  --lg-mon-orange: #f0764f;
  --lg-mon-pupil: #1a1a1f;
  /* 白（怪物眼球/高光/品牌书页/输入遮线） */
  --lg-mist: #ffffff;
  /* 标语渐变字 */
  --lg-grad-a: #c9bcff;
  --lg-grad-b: #8f7ff0;
  /* 品牌书脊橙 */
  --lg-brand-accent: #ffaf45;
  /* 背景光斑双色 */
  --lg-orb-a: #cbc0f8;
  --lg-orb-b: #baddf9;
  /* 轨道小点 / 彗星 / 主按钮渐变头 */
  --lg-dot: #b9acef;
  --lg-comet: #7c6af0;
  /* 功能浮标黄/橙渐变（紫系直接复用品牌令牌与怪物紫） */
  --lg-pill-c3a: #f7e04a;
  --lg-pill-c3b: #e5ce2e;
  --lg-pill-c4a: #f58a64;
  --lg-pill-c4b: #e86a3e;
  /* Caps Lock 提示暖底 */
  --lg-caps-bg: #fff7e8;
  --lg-caps-border: #f5d9a8;
  --lg-caps-text: #b45309;
  /* 输入框图标静置 / 边框 hover */
  --lg-field-ic: #a5a2c2;
  --lg-input-hover: #d8d3f2;
  /* 成功按钮渐变头（尾色即 --color-success） */
  --lg-done-a: #22c55e;
  /* 版权浅紫灰 */
  --lg-copy: #aba8c6;
}

/* ============ 背景光斑（B1：drift 17s/20s 反向漂浮） ============ */
.login-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  opacity: 0.45;
  animation: login-drift 17s ease-in-out infinite;
}

.login-orb-a {
  width: 560px;
  height: 560px;
  background: var(--lg-orb-a);
  top: -180px;
  right: -140px;
}

.login-orb-b {
  width: 440px;
  height: 440px;
  background: var(--lg-orb-b);
  bottom: -160px;
  right: 22%;
  animation: login-drift 20s ease-in-out -6s infinite reverse;
}

@keyframes login-drift {
  0%,
  100% {
    transform: translate(0, 0) scale(1);
  }
  33% {
    transform: translate(50px, -36px) scale(1.08);
  }
  66% {
    transform: translate(-34px, 28px) scale(0.94);
  }
}

.login-copyright {
  position: fixed;
  bottom: 14px;
  left: 0;
  right: 0;
  z-index: 30;
  text-align: center;
  font-size: 12px;
  font-weight: 600;
  color: var(--lg-copy);
  pointer-events: none;
  animation: login-fade-up 0.8s var(--ease) 1.6s backwards;
}

/* ============ 双栏布局（≥981px 双栏；≤980px 单栏见响应式段） ============ */

@media (min-width: 981px) {
  .login-layout {
    grid-template-columns: minmax(400px, 0.96fr) 1.04fr;
  }
}

/* ============ 左栏：深灰渐变 + 环境光斑 + 入场（B2/B3） ============ */
.login-chars-side {
  padding: 44px 32px 40px;
  min-height: 100vh;
  background: var(--lg-char-grad);
  animation: login-panel-in 1s var(--ease) backwards;
}

.login-env-light {
  position: absolute;
  border-radius: 50%;
  pointer-events: none;
}

.login-env-light-a {
  width: 420px;
  height: 420px;
  top: -120px;
  left: -120px;
  background: radial-gradient(
    circle,
    color-mix(in srgb, var(--lg-mist) 14%, transparent),
    transparent 65%
  );
}

.login-env-light-b {
  width: 360px;
  height: 360px;
  bottom: -100px;
  right: -90px;
  background: radial-gradient(
    circle,
    color-mix(in srgb, var(--lg-mon-purple) 18%, transparent),
    transparent 65%
  );
}

@keyframes login-panel-in {
  from {
    opacity: 0;
    transform: translateX(-40px);
  }
}

.login-brand-row {
  animation: login-fade-up 0.7s var(--ease) 0.35s backwards;
}

.login-brand-logo {
  filter: drop-shadow(0 6px 14px rgba(0, 0, 0, 0.3));
  transition: transform 0.5s var(--spring);
}

/* 品牌行 hover：logo 轻转放大（彩蛋交互） */
.login-brand-row:hover .login-brand-logo {
  transform: rotate(-8deg) scale(1.1);
}

.login-mist-fill {
  fill: var(--lg-mist);
}

.login-brand-accent-fill {
  fill: var(--lg-brand-accent);
}

.login-mist-stroke {
  stroke: var(--lg-mist);
}

.login-brand-tag {
  font-size: 10.5px;
  font-weight: 800;
  letter-spacing: 1.2px;
  color: var(--lg-mist);
  text-transform: uppercase;
  background: color-mix(in srgb, var(--lg-mist) 18%, transparent);
  backdrop-filter: blur(6px);
  padding: 5px 10px;
  border-radius: 8px;
  transform: translateY(1px);
}

.login-side-copy {
  margin: 44px 0 10px;
  animation: login-fade-up 0.8s var(--ease) 0.5s backwards;
}

.login-grad-text {
  background: linear-gradient(90deg, var(--lg-grad-a), var(--lg-grad-b));
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

@keyframes login-fade-up {
  from {
    opacity: 0;
    transform: translateY(18px);
  }
}

/* ============ 怪物舞台（站位/层级按设计稿逐帧校准：橙最前 > 黄 > 紫 > 黑） ============ */
.login-monsters-stage {
  max-width: 500px;
  animation: login-monsters-in 1s var(--calm) 0.45s backwards;
  transform-origin: bottom center;
}

@keyframes login-monsters-in {
  from {
    opacity: 0;
    transform: translateY(46px);
  }
}

.login-monster {
  position: absolute;
  bottom: 0;
  display: block;
  overflow: visible;
}

.login-monster.is-purple {
  left: 16%;
  width: 36%;
  z-index: 2;
}

.login-monster.is-black {
  left: 49%;
  width: 27%;
  z-index: 1;
}

.login-monster.is-yellow {
  left: 65%;
  width: 30%;
  z-index: 3;
}

.login-monster.is-orange {
  left: 0;
  width: 47%;
  z-index: 4;
}

/* 怪物配色：身体按身份取色（engine 只写 transform，颜色全部在此声明） */
.login-monster.is-purple .monster-body {
  fill: var(--lg-mon-purple);
}

.login-monster.is-black .monster-body {
  fill: var(--lg-mon-black);
}

.login-monster.is-yellow .monster-body {
  fill: var(--lg-mon-yellow);
}

.login-monster.is-orange .monster-body {
  fill: var(--lg-mon-orange);
}

.monster-sheen {
  fill: var(--lg-mist);
}

.monster-ball {
  fill: var(--lg-mist);
}

.monster-pupil {
  fill: var(--lg-mon-pupil);
}

.monster-glint {
  fill: var(--lg-mist);
}

.monster-mouth {
  stroke: var(--lg-mon-pupil);
  stroke-width: 5.5;
  stroke-linecap: round;
}

/* 地面投影：模糊椭圆 + 呼吸缩放（B4） */
.monster-shadow {
  fill: rgba(0, 0, 0, 0.22);
  filter: url(#login-soft-blur);
  transform-box: fill-box;
  transform-origin: 50% 50%;
  animation: login-shadow-pulse 6s ease-in-out infinite;
}

.login-monster.is-black .monster-shadow {
  fill: rgba(0, 0, 0, 0.28);
}

.login-monster.is-yellow .monster-shadow,
.login-monster.is-orange .monster-shadow {
  fill: rgba(0, 0, 0, 0.2);
}

@keyframes login-shadow-pulse {
  50% {
    transform: scale(0.96, 0.9);
    opacity: 0.8;
  }
}

/* 引擎写入点：身体 skew/tx（原点取 --lo）、五官 translate、瞳孔 translate */
.monster-lean {
  transform-box: view-box;
  transform-origin: var(--lo);
  will-change: transform;
}

.monster-face {
  will-change: transform;
}

.monster-pupil-track {
  will-change: transform;
}

/* 随机眨眼：blinking 类由引擎挂到五官容器，眼睑 scaleY 收合（B6） */
.monster-eye {
  transform-box: fill-box;
  transform-origin: center;
  transition: transform 0.09s ease;
}

.monster-face.blinking .monster-eye {
  transform: scaleY(0.06);
}

/* ============ 右栏轨道（B9~B14） ============ */
.login-stage {
  padding: 56px 44px;
}

.login-orbit-wrap {
  position: relative;
  width: min(92%, 86vmin);
  max-width: 860px;
  aspect-ratio: 1;
}

.login-plx {
  position: absolute;
  inset: 0;
  pointer-events: none;
  will-change: translate;
}

.login-orbit-in {
  position: absolute;
  inset: 0;
  animation: login-orbit-in 1.1s var(--ease) backwards;
  animation-delay: var(--d, 0s);
}

@keyframes login-orbit-in {
  from {
    opacity: 0;
    transform: scale(0.7) rotate(-18deg);
  }
}

.login-orbit-ring {
  position: absolute;
  border-radius: 50%;
}

.login-orbit-ring.is-outer {
  inset: 0;
  border: 1.5px solid color-mix(in srgb, var(--color-brand) 17%, transparent);
}

.login-circ-rotor {
  position: absolute;
  inset: 0;
  animation: login-spin 110s linear infinite;
}

.login-circ-text {
  width: 100%;
  height: 100%;
  opacity: 0.85;
}

.login-circ-copy {
  /* 环形文字色 = 品牌紫 50%（拉丁字体随全局 --font-sans 首位 Plus Jakarta Sans） */
  fill: color-mix(in srgb, var(--color-brand) 50%, transparent);
  font-weight: 700;
}

@keyframes login-spin {
  to {
    transform: rotate(360deg);
  }
}

@keyframes login-spin-rev {
  to {
    transform: rotate(-360deg);
  }
}

.login-m-dot {
  position: absolute;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--lg-dot);
  transform: translate(-50%, -50%);
  box-shadow: 0 0 10px color-mix(in srgb, var(--color-brand) 50%, transparent);
  animation: login-pulse-dot 2.6s ease-in-out infinite;
  animation-delay: var(--d, 0s);
}

@keyframes login-pulse-dot {
  50% {
    transform: translate(-50%, -50%) scale(1.7);
    opacity: 0.55;
  }
}

.login-orbit-ring.is-mid {
  inset: 13.5%;
  border: 1.6px dashed color-mix(in srgb, var(--color-brand) 30%, transparent);
  animation: login-spin 95s linear infinite;
}

.login-orbit-ring.is-inner {
  inset: 27.5%;
  border: 1.5px dashed color-mix(in srgb, var(--color-brand) 24%, transparent);
  animation: login-spin-rev 46s linear infinite;
}

.login-comet {
  position: absolute;
  top: -8px;
  left: 50%;
  transform: translateX(-50%);
}

.login-comet i {
  display: block;
  width: 13px;
  height: 13px;
  border-radius: 50%;
  background: var(--lg-comet);
  box-shadow:
    0 0 20px 5px color-mix(in srgb, var(--lg-comet) 55%, transparent),
    0 0 44px 12px color-mix(in srgb, var(--lg-comet) 22%, transparent);
  animation: login-comet-pulse 1.8s ease-in-out infinite;
}

@keyframes login-comet-pulse {
  50% {
    box-shadow:
      0 0 26px 8px color-mix(in srgb, var(--lg-comet) 70%, transparent),
      0 0 60px 18px color-mix(in srgb, var(--lg-comet) 30%, transparent);
  }
}

/* 功能浮标：白玻璃药丸 + 错峰浮动（真实功能文案，禁统计假数字） */
.login-onode {
  position: absolute;
  transform: translate(-50%, -50%);
  z-index: 3;
  animation: login-pop-in 0.8s var(--spring) backwards;
  animation-delay: var(--d, 0s);
}

@keyframes login-pop-in {
  from {
    opacity: 0;
    transform: translate(-50%, -50%) scale(0.25);
  }
}

.login-pill {
  display: flex;
  align-items: center;
  gap: 11px;
  background: color-mix(in srgb, var(--lg-mist) 82%, transparent);
  backdrop-filter: blur(10px);
  border: 1px solid color-mix(in srgb, var(--color-brand) 18%, transparent);
  border-radius: 15px;
  padding: 10px 16px 10px 11px;
  box-shadow: 0 12px 30px rgba(35, 30, 90, 0.12);
  animation: login-float-y var(--fd, 6s) ease-in-out var(--fo, 0s) infinite;
  transition:
    transform 0.35s var(--spring),
    box-shadow 0.35s ease,
    border-color 0.3s ease;
  cursor: default;
}

.login-pill:hover {
  transform: scale(1.07);
  border-color: color-mix(in srgb, var(--color-brand) 45%, transparent);
  box-shadow: 0 18px 40px rgba(35, 30, 90, 0.2);
}

@keyframes login-float-y {
  50% {
    transform: translateY(-9px);
  }
}

.login-pill-ic {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  flex: none;
  color: var(--lg-mist);
  box-shadow: 0 6px 14px rgba(35, 30, 90, 0.18);
}

.login-pill-ic.c1 {
  background: linear-gradient(135deg, var(--lg-comet), var(--color-brand-strong));
}

.login-pill-ic.c2 {
  background: linear-gradient(135deg, var(--lg-mon-purple), var(--color-brand-deep));
}

.login-pill-ic.c3 {
  background: linear-gradient(135deg, var(--lg-pill-c3a), var(--lg-pill-c3b));
}

.login-pill-ic.c4 {
  background: linear-gradient(135deg, var(--lg-pill-c4a), var(--lg-pill-c4b));
}

.login-pill b {
  display: block;
  font-size: 14.5px;
  font-weight: 800;
  letter-spacing: -0.2px;
  line-height: 1.15;
  white-space: nowrap;
}

.login-pill span {
  display: block;
  font-size: 11px;
  font-weight: 600;
  color: var(--color-text-muted);
  white-space: nowrap;
  margin-top: 1px;
}

/* ============ 表单区氛围与入场（B15/B16） ============ */
.login-form-zone {
  pointer-events: none;
}

.login-aura {
  position: absolute;
  width: min(560px, 90vw);
  height: min(560px, 90vw);
  border-radius: 50%;
  background: radial-gradient(
    circle,
    color-mix(in srgb, var(--color-brand) 14%, transparent),
    transparent 62%
  );
  animation: login-aura-breathe 6s ease-in-out infinite;
}

@keyframes login-aura-breathe {
  50% {
    transform: scale(1.08);
    opacity: 0.75;
  }
}

.login-col {
  pointer-events: auto;
  animation: login-form-in 1s var(--ease) 0.35s backwards;
}

@keyframes login-form-in {
  from {
    opacity: 0;
    transform: translateY(44px);
  }
}

.login-head {
  animation: login-fade-up 0.7s var(--ease) 0.55s backwards;
}

/* 标题逐词上浮（外层裁切，内层从 110% 升起） */
.login-word {
  display: inline-block;
  overflow: hidden;
  vertical-align: bottom;
}

.login-word i {
  display: inline-block;
  font-style: normal;
  animation: login-word-up 0.8s var(--ease) backwards;
  animation-delay: var(--d, 0s);
}

@keyframes login-word-up {
  from {
    transform: translateY(110%);
  }
}

.login-hint {
  animation: login-fade-up 0.6s var(--ease) 1s backwards;
}

/* 地面光斑 + 中线闪光 */
.login-ground {
  position: absolute;
  left: 50%;
  bottom: -52px;
  width: 135%;
  height: 96px;
  transform: translateX(-50%);
  pointer-events: none;
  z-index: -1;
}

.login-ground::before {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: 50%;
  background: radial-gradient(
    ellipse at center,
    color-mix(in srgb, var(--color-brand) 30%, transparent),
    color-mix(in srgb, var(--color-brand) 9%, transparent) 46%,
    transparent 72%
  );
  filter: blur(12px);
  animation: login-ground-pulse 5s ease-in-out infinite;
}

.login-ground::after {
  content: '';
  position: absolute;
  left: 14%;
  right: 14%;
  bottom: 16px;
  height: 1.5px;
  background: linear-gradient(
    90deg,
    transparent,
    color-mix(in srgb, var(--color-brand) 50%, transparent),
    transparent
  );
  animation: login-line-shine 5s ease-in-out infinite;
}

@keyframes login-ground-pulse {
  50% {
    opacity: 0.72;
    transform: scale(0.96);
  }
}

@keyframes login-line-shine {
  50% {
    opacity: 0.55;
  }
}

/* ============ 浮动标签字段（B17/B19：focus 上浮/光环、错误 shake） ============ */
.login-field {
  position: relative;
  margin-bottom: 16px;
  animation: login-fade-up 0.6s var(--ease) backwards;
  animation-delay: var(--d, 0s);
}

.login-input {
  width: 100%;
  height: 54px;
  border: 1.6px solid var(--color-border);
  border-radius: var(--radius-xl);
  background: var(--color-surface);
  padding: 0 48px 0 46px;
  font-size: 14.5px;
  font-weight: 600;
  color: var(--color-text);
  outline: none;
  box-shadow: 0 2px 10px rgba(35, 30, 90, 0.05);
  transition:
    border-color 0.28s ease,
    box-shadow 0.28s ease;
}

.login-input:hover {
  border-color: var(--lg-input-hover);
}

.login-input:focus {
  border-color: var(--color-brand);
  box-shadow:
    0 0 0 4px color-mix(in srgb, var(--color-brand) 14%, transparent),
    0 6px 18px color-mix(in srgb, var(--color-brand) 12%, transparent);
}

/* 浏览器 autofill 白底覆盖（防止蓝底闪现） */
.login-input:-webkit-autofill {
  -webkit-box-shadow: 0 0 0 40px var(--color-surface) inset;
}

/* 浮动标签：基态居中灰色，focus/有值时上浮为品牌色小标签（白底遮线） */
.login-label {
  position: absolute;
  left: 46px;
  top: 50%;
  transform: translateY(-50%);
  font-size: 14.5px;
  font-weight: 500;
  color: var(--color-text-subtle);
  pointer-events: none;
  transition: all 0.28s var(--ease);
}

.login-field .login-input:focus ~ .login-label,
.login-field .login-input:not(:placeholder-shown) ~ .login-label {
  top: 0;
  left: 42px;
  font-size: 11.5px;
  font-weight: 700;
  color: var(--color-brand);
  background: var(--color-surface);
  padding: 1px 7px;
  border-radius: 6px;
  box-shadow: 0 0 0 5px var(--color-surface);
}

/* 左图标：focus 时品牌色 + 轻放大 */
.login-f-ic {
  position: absolute;
  left: 16px;
  top: 50%;
  transform: translateY(-50%);
  height: 18px;
  width: 18px;
  color: var(--lg-field-ic);
  pointer-events: none;
  transition:
    color 0.28s ease,
    transform 0.3s var(--spring);
}

.login-field .login-input:focus ~ .login-f-ic {
  color: var(--color-brand);
  transform: translateY(-50%) scale(1.12);
}

/* 字段错误：红边 + 光环 + shake（keyframes 参数照设计稿：-7/+7px @20/40/60/80%） */
.login-field.is-error .login-input {
  border-color: var(--color-danger);
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--color-danger) 12%, transparent);
  animation: login-shake 0.5s ease;
}

.login-field.is-error .login-f-ic {
  color: var(--color-danger);
}

.login-field.is-error .login-input:focus ~ .login-label,
.login-field.is-error .login-input:not(:placeholder-shown) ~ .login-label {
  color: var(--color-danger);
}

@keyframes login-shake {
  20%,
  60% {
    transform: translateX(-7px);
  }
  40%,
  80% {
    transform: translateX(7px);
  }
}

/* 错误文案：max-height 展开（基态折叠不占位） */
.login-err-msg {
  max-height: 0;
  overflow: hidden;
  opacity: 0;
  transition:
    max-height 0.35s var(--ease),
    opacity 0.35s var(--ease);
  font-size: 12px;
  font-weight: 600;
  color: var(--color-danger);
  padding-left: 4px;
}

.login-field .login-err-msg.show {
  max-height: 26px;
  opacity: 1;
  margin-top: 7px;
}

/* Caps Lock 提示：暖底小胶囊右下浮出，圆点脉冲 */
.login-caps {
  position: absolute;
  right: 12px;
  top: calc(100% + 6px);
  display: flex;
  align-items: center;
  gap: 6px;
  background: var(--lg-caps-bg);
  border: 1px solid var(--lg-caps-border);
  color: var(--lg-caps-text);
  font-size: 11.5px;
  font-weight: 700;
  padding: 5px 10px;
  border-radius: 8px;
  opacity: 0;
  transform: translateY(-4px);
  pointer-events: none;
  transition: all 0.3s var(--ease);
  z-index: 4;
}

.login-caps.show {
  opacity: 1;
  transform: none;
}

.login-caps i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-warning);
  animation: login-caps-pulse 1s ease-in-out infinite;
}

@keyframes login-caps-pulse {
  50% {
    transform: scale(1.6);
  }
}

/* 密码眼睛：双色标交叉淡入切换 */
.login-eye {
  position: absolute;
  right: 9px;
  top: 50%;
  transform: translateY(-50%);
  width: 38px;
  height: 38px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  color: var(--lg-field-ic);
  transition:
    color 0.25s ease,
    background 0.25s ease,
    transform 0.25s ease;
}

.login-eye:hover {
  color: var(--color-brand);
  background: var(--color-brand-soft);
  transform: translateY(-50%) scale(1.08);
}

.login-eye .ic-on,
.login-eye .ic-off {
  position: absolute;
  transition:
    opacity 0.25s ease,
    transform 0.35s var(--spring);
}

.login-eye .ic-off {
  opacity: 0;
  transform: scale(0.5) rotate(-40deg);
}

.login-eye.show .ic-on {
  opacity: 0;
  transform: scale(0.5) rotate(40deg);
}

.login-eye.show .ic-off {
  opacity: 1;
  transform: none;
}

/* ============ 提交按钮（B22：渐变 + shine / loading / done 三态） ============ */
.login-submit {
  position: relative;
  width: 100%;
  height: 54px;
  border-radius: var(--radius-xl);
  color: var(--lg-mist);
  font-size: 15px;
  font-weight: 800;
  letter-spacing: 0.2px;
  background: linear-gradient(135deg, var(--lg-comet), var(--color-brand-strong));
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  box-shadow: var(--shadow-brand);
  transition:
    transform 0.3s var(--spring),
    box-shadow 0.3s ease;
  animation: login-fade-up 0.6s var(--ease) 0.92s backwards;
}

.login-submit:hover:not(:disabled) {
  transform: translateY(-3px);
  box-shadow: 0 18px 36px color-mix(in srgb, var(--color-brand) 48%, transparent);
}

.login-submit:active:not(:disabled) {
  transform: translateY(0) scale(0.97);
}

/* shine 扫光：hover 触发白色斜条左→右掠过 */
.login-submit::before {
  content: '';
  position: absolute;
  top: 0;
  left: -80%;
  width: 50%;
  height: 100%;
  background: linear-gradient(
    105deg,
    transparent,
    color-mix(in srgb, var(--lg-mist) 35%, transparent),
    transparent
  );
  transform: skewX(-22deg);
  transition: left 0.65s var(--ease);
}

.login-submit:hover::before {
  left: 130%;
}

.login-submit .arr {
  transition: transform 0.3s var(--spring);
}

.login-submit:hover .arr {
  transform: translateX(5px);
}

.login-submit .spinner {
  display: none;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  border: 2.5px solid color-mix(in srgb, var(--lg-mist) 35%, transparent);
  border-top-color: var(--lg-mist);
  animation: login-spin 0.7s linear infinite;
}

.login-submit.is-loading {
  pointer-events: none;
}

.login-submit.is-loading .txt,
.login-submit.is-loading .arr {
  display: none;
}

.login-submit.is-loading .spinner {
  display: block;
}

.login-submit.is-done {
  background: linear-gradient(135deg, var(--lg-done-a), var(--color-success));
  box-shadow: 0 12px 26px color-mix(in srgb, var(--color-success) 42%, transparent);
}

/* ============ 登录成功 overlay（B24 压缩版：总时长约 1s） ============ */
.login-success-overlay {
  position: fixed;
  inset: 0;
  z-index: 100;
  background: color-mix(in srgb, var(--color-bg) 90%, transparent);
  backdrop-filter: blur(14px);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  animation: login-overlay-in 0.5s var(--ease) backwards;
}

@keyframes login-overlay-in {
  from {
    opacity: 0;
  }
}

.login-suc-ring {
  width: 100px;
  height: 100px;
  animation: login-suc-ring-pop 0.55s var(--spring) 0.1s backwards;
}

@keyframes login-suc-ring-pop {
  from {
    transform: scale(0.4);
    opacity: 0;
  }
}

/* 圆环描线：周长 2πr≈289，从满偏移画到 0 */
.login-suc-ring circle {
  fill: none;
  stroke: var(--color-success);
  stroke-width: 5;
  stroke-linecap: round;
  stroke-dasharray: 289;
  transform: rotate(-90deg);
  transform-origin: center;
  animation: login-draw 0.6s var(--ease) 0.15s backwards;
}

/* 对勾描线：路径长约 60 */
.login-suc-ring path {
  fill: none;
  stroke: var(--color-success);
  stroke-width: 7;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-dasharray: 60;
  animation: login-draw 0.4s var(--ease) 0.6s backwards;
}

@keyframes login-draw {
  from {
    /* 圆环周长 289 / 对勾路径长 60：基态为描线完成（offset 0），动画从满偏移画起 */
    stroke-dashoffset: var(--dash-from, 289);
  }
}

.login-suc-title {
  font-size: 22px;
  font-weight: 800;
  animation: login-suc-rise 0.5s var(--ease) 0.7s backwards;
}

.login-suc-sub {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--color-text-muted);
  animation: login-suc-rise 0.5s var(--ease) 0.8s backwards;
}

@keyframes login-suc-rise {
  from {
    opacity: 0;
    transform: translateY(14px);
  }
}

.login-suc-bar {
  width: 190px;
  height: 5px;
  border-radius: 5px;
  background: var(--color-surface);
  overflow: hidden;
  margin-top: 8px;
  box-shadow: 0 2px 8px rgba(35, 30, 90, 0.08);
  animation: login-fade-in 0.4s ease 0.9s backwards;
}

.login-suc-bar i {
  display: block;
  height: 100%;
  border-radius: 5px;
  background: linear-gradient(90deg, var(--lg-comet), var(--color-brand));
  transform: scaleX(0);
  transform-origin: left;
  animation: login-suc-bar-fill 0.5s var(--ease) 0.5s forwards;
}

@keyframes login-suc-bar-fill {
  from {
    transform: scaleX(0);
  }
  to {
    transform: scaleX(1);
  }
}

@keyframes login-fade-in {
  from {
    opacity: 0;
  }
}

/* ============ 响应式（B26：1240 / 980 / 430 三档） ============ */
@media (max-width: 1240px) {
  .login-chars-side {
    padding: 40px 22px 36px;
  }

  .login-copy-lead {
    display: none;
  }

  .login-side-copy {
    margin: 30px 0 4px;
  }
}

@media (max-width: 980px) {
  .login-chars-side {
    min-height: 0;
    padding: 88px 12px 0;
  }

  .login-brand-row {
    position: absolute;
    top: 18px;
    left: 18px;
  }

  .login-side-copy {
    display: none;
  }

  .login-monsters-stage {
    margin: 0 auto;
    width: min(86%, 380px);
    max-width: none;
  }

  .login-monsters {
    height: clamp(120px, 24vw, 170px);
  }

  .login-stage {
    min-height: auto;
    padding: 34px 16px 90px;
  }

  /* 轨道退化为超大半透明背景装饰；浮标/环形文字/脉冲点隐藏 */
  .login-onode,
  .login-circ-rotor,
  .login-m-dot {
    display: none;
  }

  /* 表单层改回流内布局：单栏下由内容撑高，避免绝对定位撑不开容器 */
  .login-form-zone {
    position: static;
  }

  .login-orbit-wrap {
    width: 160vmax;
    opacity: 0.5;
    position: absolute;
    pointer-events: none;
  }

  .login-copyright {
    display: none;
  }
}

@media (max-width: 430px) {
  .login-head h1 {
    font-size: 23px;
  }
}
</style>
