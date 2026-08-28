/**
 * 意图元数据（仪表盘意图 donut / 意图×赞踩堆叠条共用）
 *
 * 职责：后端意图体系（knowledge_question / chat / unknown，见 docs/superpowers/specs/
 * 意图体系设计）的中文标签与图表色序位集中定义，保证两处图表同一意图颜色一致。
 * 色序位对应 @theme 图表语义令牌 --color-chart-series-1/2/3（主紫 → 浅紫 → 最浅紫）。
 */

/** 意图元数据项 */
export interface IntentMeta {
  /** 后端意图枚举值（feedbacks/stats 的 intentType 字段取值） */
  value: string
  /** 中文标签（用户可见） */
  label: string
  /** 图例圆点色（Tailwind 静态字面量类，值来自 @theme 图表序列令牌） */
  dotClass: string
  /** donut 扇区色序位（scoped CSS .seg-0/1/2 对应 series-1/2/3） */
  toneIndex: number
}

/** 已知意图的稳定展示顺序（知识问答 → 闲聊 → 未知意图） */
export const INTENT_META: IntentMeta[] = [
  { value: 'knowledge_question', label: '知识问答', dotClass: 'bg-chart-series-1', toneIndex: 0 },
  { value: 'chat', label: '闲聊', dotClass: 'bg-chart-series-2', toneIndex: 1 },
  { value: 'unknown', label: '未知意图', dotClass: 'bg-chart-series-3', toneIndex: 2 },
]

/**
 * 按意图枚举值取元数据
 *
 * @param value 后端 intentType（来自 feedbacks/stats，不允许为空）
 * @returns 已知意图返回 INTENT_META 对应项；未知枚举回退「原值 + 末位色」防渲染崩溃
 */
export function intentMetaOf(value: string): IntentMeta {
  return (
    INTENT_META.find((m) => m.value === value) ?? {
      value,
      label: value,
      dotClass: 'bg-chart-series-3',
      toneIndex: 2,
    }
  )
}
