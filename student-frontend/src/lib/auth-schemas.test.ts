/**
 * 认证表单 schema 与密码强度纯函数测试（边界值 + 中文提示口径）
 */
import { describe, expect, it } from "vitest";
import { loginFormSchema, registerFormSchema, sendCodeSchema } from "./auth-schemas";
import { scorePassword, strengthHint } from "./password-strength";

describe("loginFormSchema", () => {
  it("合法输入通过（含前后空白裁剪）", () => {
    const parsed = loginFormSchema.safeParse({ account: "  wenqu ", password: "123456" });
    expect(parsed.success).toBe(true);
    if (parsed.success) {
      expect(parsed.data.account).toBe("wenqu");
    }
  });

  it("空用户名与短密码分别给出中文错误", () => {
    const parsed = loginFormSchema.safeParse({ account: "", password: "1" });
    expect(parsed.success).toBe(false);
    if (!parsed.success) {
      expect(parsed.error.issues.map((i) => i.message)).toContain("请输入用户名或邮箱");
      expect(parsed.error.issues.map((i) => i.message)).toContain("密码至少 6 位");
    }
  });
});

describe("sendCodeSchema", () => {
  it("邮箱格式校验 + 小写归一化", () => {
    const ok = sendCodeSchema.safeParse({ email: " Zhang.San@Example.COM " });
    expect(ok.success).toBe(true);
    if (ok.success) {
      expect(ok.data.email).toBe("zhang.san@example.com");
    }
    expect(sendCodeSchema.safeParse({ email: "not-an-email" }).success).toBe(false);
  });
});

describe("registerFormSchema", () => {
  /** 合法基线载荷（用例内逐字段覆盖构造反例） */
  function valid(overrides: Record<string, unknown> = {}) {
    return {
      nickname: "同学B",
      email: "b@example.com",
      code: "654321",
      password: "Password-88",
      terms: true,
      ...overrides,
    };
  }

  it("完整合法载荷通过，昵称空串归一化为 undefined（服务端回退邮箱前缀）", () => {
    const parsed = registerFormSchema.safeParse(valid({ nickname: "   " }));
    expect(parsed.success).toBe(true);
    if (parsed.success) {
      expect(parsed.data.nickname).toBeUndefined();
      expect(parsed.data.email).toBe("b@example.com");
    }
  });

  it("验证码必须为 6 位数字；密码下限 8 位；terms 必须勾选", () => {
    for (const bad of [
      valid({ code: "12345" }),
      valid({ code: "12a456" }),
      valid({ password: "short7" }),
      valid({ terms: false }),
    ]) {
      expect(registerFormSchema.safeParse(bad).success).toBe(false);
    }
  });

  it("昵称超长拒绝并提示上限", () => {
    const parsed = registerFormSchema.safeParse(valid({ nickname: "超".repeat(51) }));
    expect(parsed.success).toBe(false);
    if (!parsed.success) {
      expect(parsed.error.issues[0].message).toContain("50");
    }
  });
});

describe("密码强度评分", () => {
  it("与设计稿四档规则一致：长度/大小写/数字/特殊符号逐项累加", () => {
    expect(scorePassword("")).toBe(0);
    expect(scorePassword("abcdefgh")).toBe(1); // 仅 ≥8 位
    expect(scorePassword("Abcdefgh")).toBe(2); // 大小写
    expect(scorePassword("Abcdefg1")).toBe(3); // 数字
    expect(scorePassword("Abcdefg1!@")).toBe(4); // 特殊符号且 ≥10 位
  });

  it("强度文案随档位变化（空密码落在最弱档提示）", () => {
    expect(strengthHint(0)).toContain("8 位");
    expect(strengthHint(3)).toBe("强密码。");
    // 越界收敛到合法区间
    expect(strengthHint(9)).toBe("非常强。");
  });
});
