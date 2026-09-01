/**
 * 剪贴板复制工具（BUG-27 降级路径）
 *
 * navigator.clipboard 仅安全上下文（HTTPS / localhost）可用：HTTP 内网部署等
 * 非安全上下文下该属性为 undefined，直接调用会抛 TypeError，且调用侧以
 * `void copy(...)` 触发时成为未处理 Promise 拒绝——点击复制静默无响应。
 *
 * 本工具提供两级降级，调用方按返回值决定 toast 文案：
 * 1. 优先 navigator.clipboard.writeText（安全上下文首选，异步非阻塞）；
 * 2. 不可用或失败（权限拒绝/文档失焦）→ 回退 document.execCommand("copy")
 *    （textarea 中转的遗留同步 API，非安全上下文兜底）；
 * 3. 两条路径均失败 → 返回 false，调用方提示「复制失败，请手动复制」。
 *
 * 线程安全：无共享状态，纯 DOM 操作可并发调用。
 */

/**
 * 复制文本到剪贴板（带降级链）
 *
 * @param text 待复制文本（任意长度；非空内容由调用方保证业务语义）
 * @returns 是否复制成功（false = clipboard API 与 execCommand 均失败，
 *   调用方须 toast 提示用户手动复制）
 */
export async function copyToClipboard(text: string): Promise<boolean> {
  // 一级：异步 clipboard API（存在性类型守卫——非安全上下文下为 undefined）
  if (typeof navigator !== "undefined" && navigator.clipboard) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      // 权限拒绝 / 文档失焦等运行时失败：落入 execCommand 降级，不中断链路
    }
  }
  // 二级：execCommand 同步复制（textarea 中转；fixed 视口外定位避免页面滚动跳动）
  try {
    const textarea = document.createElement("textarea");
    textarea.value = text;
    textarea.style.position = "fixed";
    textarea.style.top = "-9999px";
    // 只读防移动端聚焦弹键盘（iOS 中转惯例）
    textarea.setAttribute("readonly", "");
    document.body.appendChild(textarea);
    try {
      textarea.select();
      return document.execCommand("copy");
    } finally {
      // 中转节点用毕即移除（select/execCommand 抛异常也不残留 DOM）
      document.body.removeChild(textarea);
    }
  } catch {
    // execCommand 不可用（极端环境）或复制被拒：判定为复制失败
    return false;
  }
}
