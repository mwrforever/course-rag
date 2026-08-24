/**
 * 相对时间格式化与会话时间分组测试（首页最近会话与会话管理共用，设计 §1.5.5）
 *
 * 用例以固定 now 注入（2026-08-24 12:00），覆盖：刚刚 / n 分钟前 / n 小时前 /
 * 昨天 HH:mm / M月D日 / 无效时间串容错；时间分组：今天 / 昨天 / 本周 / 更早
 * （本周 = 本周一至今天，排除今天与昨天；昨日判定按日历日优先于本周）。
 */
import { describe, expect, it } from "vitest";
import { formatRelativeTime, groupSessionTime } from "./time";

/** 固定「当前时刻」，保证断言确定性（2026-08-24 为周一） */
const NOW = new Date("2026-08-24T12:00:00");
/** 固定「当前时刻」二：2026-08-27 为周四（本周三/周二可作「本周」样例） */
const NOW_THURSDAY = new Date("2026-08-27T12:00:00");

describe("formatRelativeTime 相对时间", () => {
  it("1 分钟内 → 刚刚", () => {
    expect(formatRelativeTime("2026-08-24T11:59:40", NOW)).toBe("刚刚");
  });

  it("1 小时内 → n 分钟前", () => {
    expect(formatRelativeTime("2026-08-24T11:15:00", NOW)).toBe("45 分钟前");
  });

  it("同日超过 1 小时 → n 小时前", () => {
    expect(formatRelativeTime("2026-08-24T08:00:00", NOW)).toBe("4 小时前");
  });

  it("昨天 → 昨天 HH:mm", () => {
    expect(formatRelativeTime("2026-08-23T18:05:00", NOW)).toBe("昨天 18:05");
  });

  it("更早 → M月D日", () => {
    expect(formatRelativeTime("2026-08-01T09:00:00", NOW)).toBe("8月1日");
  });

  it("无效时间串 → 空串（调用方自行回退）", () => {
    expect(formatRelativeTime("not-a-date", NOW)).toBe("");
  });

  it("时钟轻微偏差（未来串）按刚刚容错", () => {
    expect(formatRelativeTime("2026-08-24T12:00:30", NOW)).toBe("刚刚");
  });
});

describe("groupSessionTime 会话时间分组（今天/昨天/本周/更早）", () => {
  it("同日 → 今天", () => {
    expect(groupSessionTime("2026-08-24T08:00:00", NOW)).toBe("today");
  });

  it("昨天 → yesterday", () => {
    expect(groupSessionTime("2026-08-23T18:00:00", NOW)).toBe("yesterday");
  });

  it("本周一/本周二（非今昨）→ thisWeek", () => {
    // NOW_THURSDAY = 2026-08-27（周四）：本周一为 08-24，08-24/08-25 属本周且非今昨；08-26 是昨天
    expect(groupSessionTime("2026-08-24T10:00:00", NOW_THURSDAY)).toBe("thisWeek");
    expect(groupSessionTime("2026-08-25T10:00:00", NOW_THURSDAY)).toBe("thisWeek");
  });

  it("本周一之前（上周日等）→ earlier", () => {
    expect(groupSessionTime("2026-08-23T10:00:00", NOW_THURSDAY)).toBe("earlier");
    expect(groupSessionTime("2026-08-10T10:00:00", NOW)).toBe("earlier");
  });

  it("昨天判定优先于本周：周一场景昨天=上周日仍落「昨天」（日历日语义）", () => {
    // NOW 为周一 08-24：日历昨天是周日 08-23，昨天分组优先于本周边界
    expect(groupSessionTime("2026-08-23T23:00:00", NOW)).toBe("yesterday");
  });

  it("无效时间串 → 更早（归类兜底）", () => {
    expect(groupSessionTime("not-a-date", NOW)).toBe("earlier");
  });
});
