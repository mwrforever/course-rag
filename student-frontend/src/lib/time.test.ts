/**
 * 相对时间格式化测试（首页最近会话与后续会话管理共用，设计 §1.5.5 时间分组）
 *
 * 用例以固定 now 注入（2026-08-24 12:00），覆盖：刚刚 / n 分钟前 / n 小时前 /
 * 昨天 HH:mm / M月D日 / 无效时间串容错。
 */
import { describe, expect, it } from "vitest";
import { formatRelativeTime } from "./time";

/** 固定「当前时刻」，保证断言确定性 */
const NOW = new Date("2026-08-24T12:00:00");

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
