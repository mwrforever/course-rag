/**
 * 页内错误横幅（设计文档 §1.7 Error）
 *
 * 供课程列表页与课程工作台共用：danger-soft 底 + 「服务暂时不可用，请稍后重试」+ [重试]；
 * 与首页局部实现保持同构语义（503/网络错误统一文案，401 走全局登出流不在此处理）。
 */
export interface SectionErrorProps {
  /** 重试回调（对应查询 refetch） */
  onRetry: () => void;
}

/**
 * 区块错误横幅
 *
 * @param onRetry 重试回调（点击后触发对应查询重新拉取）
 */
export function SectionError({ onRetry }: SectionErrorProps) {
  return (
    <div
      role="alert"
      className="flex items-center justify-between gap-4 rounded-xl border border-danger/30 bg-danger/5 px-4 py-3"
    >
      <p className="text-sm text-danger">服务暂时不可用，请稍后重试</p>
      <button
        type="button"
        onClick={onRetry}
        className="shrink-0 rounded-xl border border-danger/30 bg-surface px-3 py-1.5 text-sm font-medium text-danger transition-colors hover:bg-danger/10 focus-visible:ring-2 focus-visible:ring-danger"
      >
        重试
      </button>
    </div>
  );
}
