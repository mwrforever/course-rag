/**
 * 密码强度评分 —— 纯函数（无 React/DOM 依赖，登录页注册面板强度计数据源）
 *
 * 规则与设计稿一致（0–4 档）：
 * ≥8 位记 1；同时含大小写字母记 2；含数字记 3；
 * 含特殊符号且长度 ≥10 记 4。
 */

/** 强度档位文案（UI 展示提示语） */
const STRENGTH_HINTS = [
  "建议 8 位以上，混合字母与数字。",
  "偏弱——增加字符种类试试。",
  "不错，再进一步。",
  "强密码。",
  "非常强。",
] as const;

/**
 * 计算密码强度档位
 *
 * @param password 待评估的明文密码
 * @returns 0（空串/极弱）至 4 的整数档位
 */
export function scorePassword(password: string): number {
  let score = 0;
  if (password.length >= 8) score++;
  if (/[A-Z]/.test(password) && /[a-z]/.test(password)) score++;
  if (/\d/.test(password)) score++;
  if (/[^A-Za-z0-9]/.test(password) && password.length >= 10) score++;
  return Math.min(score, STRENGTH_HINTS.length - 1);
}

/**
 * 取某档位对应的提示文案
 *
 * @param score 档位（越界自动收敛到合法区间）
 * @returns 中文提示字符串
 */
export function strengthHint(score: number): string {
  return STRENGTH_HINTS[Math.min(Math.max(score, 0), STRENGTH_HINTS.length - 1)];
}
