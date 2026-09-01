/**
 * C 端轻量日志封装（唯一 console 出口）
 *
 * 背景：eslint 全局 no-console（eslint.config.mjs 注释「生产代码禁用 console：
 * 日志统一走封装，避免散落的调试输出进入主干」），本模块即该「封装」的落地。
 * 生产前端暂无外部日志 SDK 依赖，warn 直接映射 console.warn；后续如需
 * 采样/分级上报，收敛在本模块内调整而不动调用方。
 *
 * 线程安全：无状态纯转发，无共享可变状态。
 */

/**
 * 警告级日志：静默降级/数据异常等不影响主流程但需要诊断线索的场景
 *
 * @param message 中文日志内容（须包含业务标识与降级原因，禁含密码/token 等敏感值）
 */
export function warn(message: string): void {
  // 本文件为唯一 console 豁免点（见 eslint.config.mjs 对 src/lib/logger.ts 的覆写段）
  console.warn(message);
}
