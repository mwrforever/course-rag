/**
 * 相对时间中文格式化与会话时间分组（首页最近会话与会话管理共用，设计 §1.5.5）
 *
 * formatRelativeTime：1 分钟内 → 刚刚；1 小时内 → n 分钟前；同日超 1 小时 → n 小时前；
 * 昨天 → 昨天 HH:mm；更早 → M月D日；无效时间串 → 空串（调用方自行回退）。
 * groupSessionTime：今天 / 昨天 / 本周（周一至今，排除今昨）/ 更早；无效串归更早。
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

/** 会话时间分组取值：今天 / 昨天 / 本周（周一至今，排除今昨）/ 更早 */
export type SessionTimeGroup = "today" | "yesterday" | "thisWeek" | "earlier";

/**
 * 会话时间分组（设计 §1.5.5：按 lastMessageAt ?? createdAt 归类）
 *
 * 规则（本地时区日历日）：
 * - 今天：与 now 同日
 * - 昨天：与 now 的前一天同日
 * - 本周：不早于本周一（含周一至 now 前一天），排除今天与昨天
 * - 更早：早于本周一；无效时间串也归此兜底
 *
 * @param iso 后端 ISO-8601 无时区时间串（LocalDateTime）
 * @param now 当前时刻（默认取实时；测试注入固定值保证确定性）
 * @returns 分组键（页面按 今天/昨天/本周/更早 固定顺序渲染）
 */
export function groupSessionTime(iso: string, now: Date = new Date()): SessionTimeGroup {
  const time = new Date(iso);
  if (Number.isNaN(time.getTime())) {
    return "earlier";
  }
  if (isSameDay(time, now)) {
    return "today";
  }
  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1);
  if (isSameDay(time, yesterday)) {
    return "yesterday";
  }
  // 本周一（getDay() 0=周日，距周一的天数 = (getDay()+6)%7）
  const monday = new Date(
    now.getFullYear(),
    now.getMonth(),
    now.getDate() - ((now.getDay() + 6) % 7),
  );
  return time >= monday ? "thisWeek" : "earlier";
}
