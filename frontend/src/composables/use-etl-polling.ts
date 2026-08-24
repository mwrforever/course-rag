/**
 * ETL 轮询决策 composable（设计 §2.4.2 文档管理：vue-query refetchInterval 5s 轮询）
 *
 * 职责：根据当前文档列表是否包含「非终态行」决定 vue-query 的 refetchInterval 值。
 * - 列表存在 PENDING/PARSING/PARSED/CHUNKING/CHUNKED/EMBEDDING 任一 → 返回 5000ms，
 *   查询按 5s 间隔自动刷新直到状态收敛；
 * - 列表为空或全部为终态（INDEXED/FAILED）→ 返回 false，vue-query 停止轮询。
 *
 * 设计说明：brief 契约签名 useEtlPolling(queryKey) 的语义由「列表数据源」承担，
 * 页面把 useQuery 的结果 data（records 列表）作为响应式源传入，composable 只做
 * 纯函数判定并返回 ComputedRef 供 refetchInterval 消费（vue-query 选项支持 Ref，
 * 无需访问 query client 缓存，天然可测）。
 *
 * 线程安全注意：纯 computed 派生，无共享可变状态，多实例并发安全。
 */
import { computed, toValue } from 'vue'

import type { ComputedRef, MaybeRefOrGetter } from 'vue'
import type { DocumentParseStatus, DocumentVO } from '@/lib/types'

/** ETL 轮询间隔（毫秒）：列表含非终态行时按此频率刷新（设计 §2.4.2） */
export const ETL_POLLING_INTERVAL_MS = 5000

/**
 * 非终态（工作态）判定：六态为真，INDEXED/FAILED 为假（设计 §2.5 八态体系）
 *
 * @param status 文档解析状态；undefined（列表未加载）视为无工作态
 * @returns true 表示该行仍在 ETL 管道中，需要继续轮询
 */
export function isEtlActive(status: DocumentParseStatus | undefined): boolean {
  return (
    status === 'PENDING' ||
    status === 'PARSING' ||
    status === 'PARSED' ||
    status === 'CHUNKING' ||
    status === 'CHUNKED' ||
    status === 'EMBEDDING'
  )
}

/**
 * vue-query refetchInterval 决策（页面 useQuery 选项直接消费返回值）
 *
 * @param list 当前列表文档的响应式源：可为 Ref / 普通数组 / getter（页面传
 *   () => query.data.value?.records 或镜像 ref）；undefined 视为未加载，不轮询
 * @param intervalMs 轮询间隔（默认 5000ms，测试或定制场景可注入覆盖）
 * @returns ComputedRef<number | false>：存在非终态行 → intervalMs；否则 false（停止轮询）
 */
export function useEtlPolling(
  list: MaybeRefOrGetter<readonly DocumentVO[] | undefined>,
  intervalMs = ETL_POLLING_INTERVAL_MS,
): ComputedRef<number | false> {
  return computed(() => {
    // 未加载（undefined）按空列表处理，避免误启动轮询
    const records = toValue(list) ?? []
    return records.some((doc) => isEtlActive(doc.parseStatus)) ? intervalMs : false
  })
}
