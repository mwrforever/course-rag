import { ref } from 'vue'
import { describe, expect, it } from 'vitest'

import { ETL_POLLING_INTERVAL_MS, isEtlActive, useEtlPolling } from '@/composables/use-etl-polling'

import type { DocumentParseStatus, DocumentVO } from '@/lib/types'

/**
 * ETL 轮询 composable 测试（Task 18 核心，lines 100% 目标）
 *
 * 覆盖契约（设计 §2.4.2 + 任务 brief「轮询启停」）：
 * 1. 列表存在非终态行（六工作态任一）→ refetchInterval 5000ms
 * 2. 全终态（INDEXED/FAILED）/ 空列表 / 未加载 → false（vue-query 停止轮询）
 * 3. 响应式：列表数据变化时 interval 同步切换
 * 4. 手动覆盖：getter 数据源 + 自定义间隔注入
 */

/** 文档工厂（parseStatus 可变，其余字段固定占位） */
function doc(status: DocumentParseStatus): DocumentVO {
  return {
    id: 'd-1',
    kbId: 'kb-1',
    title: '测试文档.md',
    fileType: 'md',
    fileSize: '1024',
    parseStatus: status,
    chunkCount: 0,
    errorMessage: '',
    metadataJson: '',
    courseId: null,
    createdBy: '1001',
    createdAt: '2026-08-24T10:00:00',
    updatedAt: '2026-08-24T10:00:00',
  }
}

describe('isEtlActive：非终态判定（设计 §2.5 八态）', () => {
  it('六个工作态为真：PENDING/PARSING/PARSED/CHUNKING/CHUNKED/EMBEDDING', () => {
    const working: DocumentParseStatus[] = [
      'PENDING',
      'PARSING',
      'PARSED',
      'CHUNKING',
      'CHUNKED',
      'EMBEDDING',
    ]
    for (const status of working) {
      expect(isEtlActive(status)).toBe(true)
    }
  })

  it('终态 INDEXED/FAILED 与 undefined 为假', () => {
    expect(isEtlActive('INDEXED')).toBe(false)
    expect(isEtlActive('FAILED')).toBe(false)
    expect(isEtlActive(undefined)).toBe(false)
  })
})

describe('useEtlPolling：refetchInterval 启停', () => {
  it('列表含非终态行 → 默认 5000ms（与常量一致）', () => {
    const records = ref<DocumentVO[]>([doc('PENDING'), doc('INDEXED')])
    const interval = useEtlPolling(records)
    expect(interval.value).toBe(ETL_POLLING_INTERVAL_MS)
    expect(interval.value).toBe(5000)
  })

  it('全终态 / 空列表 / 未加载（undefined）→ false 停止轮询', () => {
    expect(useEtlPolling([doc('INDEXED'), doc('FAILED')]).value).toBe(false)
    expect(useEtlPolling([]).value).toBe(false)
    expect(useEtlPolling(undefined).value).toBe(false)
  })

  it('响应式切换：非终态 → 全终态自动停，再出现工作态重启', () => {
    const records = ref<DocumentVO[]>([doc('EMBEDDING')])
    const interval = useEtlPolling(records)
    expect(interval.value).toBe(5000)

    // 全部行进入终态 → 停
    records.value = [doc('INDEXED')]
    expect(interval.value).toBe(false)

    // 上传新文档进入工作态 → 重启
    records.value = [doc('INDEXED'), doc('PARSED')]
    expect(interval.value).toBe(5000)
  })

  it('支持 getter 数据源与自定义间隔（手动覆盖）', () => {
    const records = ref<DocumentVO[]>([doc('CHUNKING')])
    const interval = useEtlPolling(() => records.value, 3000)
    expect(interval.value).toBe(3000)

    // getter 读最新数据
    records.value = [doc('INDEXED')]
    expect(interval.value).toBe(false)
  })
})
