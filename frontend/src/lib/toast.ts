/**
 * toast 轻提示（设计 §2.6）
 *
 * 右上角固定容器，success/danger/info 三色左边条，3 秒自动消失（MOTION 2 无动画库）。
 * 全局登出流（api 401 刷新失败 → toast「登录已失效，请重新登录」）与后续页面提示复用本实现。
 * 非线程安全注意：纯 DOM 操作，浏览器单线程模型下无并发问题。
 */

/** toast 语义类型：success 成功 / danger 危险 / info 提示（三色左边条） */
export type ToastType = 'success' | 'danger' | 'info'

/** toast 展示时长（毫秒）：3 秒自动消失（设计 §2.6） */
const TOAST_DURATION = 3000

/** 右上角容器（惰性创建，全部 toast 消失后随容器一并移除） */
let container: HTMLDivElement | null = null

/** 语义类型 → 左边条颜色类（Tailwind v4 静态扫描，必须为完整字面量） */
const borderClass: Record<ToastType, string> = {
  success: 'border-success',
  danger: 'border-danger',
  info: 'border-info',
}

/**
 * 弹出 toast 提示
 *
 * @param message 提示文案（全局登出统一文案「登录已失效，请重新登录」由调用方传入）
 * @param type 语义类型：缺省 info（信息提示）
 * @returns 无返回值；3 秒后自动从容器移除
 */
export function showToast(message: string, type: ToastType = 'info'): void {
  // 惰性创建右上角容器（fixed 定位，避免依赖布局上下文）
  if (!container || !document.body.contains(container)) {
    container = document.createElement('div')
    container.dataset.toastContainer = 'true'
    container.className = 'fixed right-4 top-4 z-50 flex w-80 flex-col gap-2'
    document.body.appendChild(container)
  }

  // 单条 toast：surface 底 + 语义色左条 + 阴影（设计 §2.6 造型）
  const el = document.createElement('div')
  el.dataset.toast = 'true'
  el.className = `rounded-md border-l-4 bg-surface px-4 py-3 text-sm text-text shadow-md ${borderClass[type]}`
  el.textContent = message
  container.appendChild(el)

  // 3 秒后移除本条；容器内无残留时一并移除容器
  window.setTimeout(() => {
    el.remove()
    if (container && !container.hasChildNodes()) {
      container.remove()
      container = null
    }
  }, TOAST_DURATION)
}
