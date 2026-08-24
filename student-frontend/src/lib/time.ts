/**
 * 相对时间中文格式化（首页最近会话与后续会话管理共用，设计 §1.5.5 时间分组）
 *
 * 规则：1 分钟内 → 刚刚；1 小时内 → n 分钟前；同日超 1 小时 → n 小时前；
 * 昨天 → 昨天 HH:mm；更早 → M月D日；无效时间串 → 空串（调用方自行回退）。
 * 后端时间格式为 ISO-8601 无时区串（"2026-08-24T10:15:30"），new Date 按本地时区解析。
 */

/**
 * 两个日期是否同一天（按本地时区比较年月日）
 * @param a 日期 A
 * @param b 日期 B
 * @returns 同日为 true
 */
function isSameDay(a: Date, b: Date): boolean {
  return (
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate()
  );
}

/** 补零两位（时间戳 HH:mm 展示用） */
function pad2(value: number): string {
  return String(value).padStart(2, "0");
}

/**
 * 格式化相对时间
 * @param iso 后端 ISO-8601 无时区时间串（LocalDateTime）
 * @param now 当前时刻（默认取实时；测试注入固定值保证确定性）
 * @returns 中文相对时间文案；无效输入返回空串
 */
export function formatRelativeTime(iso: string, now: Date = new Date()): string {
  const time = new Date(iso);
  if (Number.isNaN(time.getTime())) {
    return "";
  }
  const diffMs = now.getTime() - time.getTime();
  // 1 分钟内（含时钟轻微偏差的未来时间）统一按「刚刚」容错
  if (diffMs < 60_000) {
    return "刚刚";
  }
  const minutes = Math.floor(diffMs / 60_000);
  if (minutes < 60) {
    return `${minutes} 分钟前`;
  }
  if (isSameDay(time, now)) {
    return `${Math.floor(diffMs / 3_600_000)} 小时前`;
  }
  // 昨天：跨日但落在昨日日历区间（含不足 24h 的跨日场景）
  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1);
  if (isSameDay(time, yesterday)) {
    return `昨天 ${pad2(time.getHours())}:${pad2(time.getMinutes())}`;
  }
  return `${time.getMonth() + 1}月${time.getDate()}日`;
}
