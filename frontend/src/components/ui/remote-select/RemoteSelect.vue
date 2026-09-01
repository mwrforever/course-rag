<script lang="ts">
/**
 * PERF-24：空关键字首屏候选的模块级短 TTL 记忆（模块级状态放普通 script 块，
 * 与 AdminLayout 的静态表导出同构——script setup 内的顶层变量是实例级，跨实例不共享）。
 *
 * 业务取舍：表单场景首屏候选（课程/KB 列表）30s 内新鲜度损失可接受，
 * 以 30s 记忆换同一字段反复开关下拉 0 重复请求；真关键字搜索必须实时，不进缓存。
 */
const EMPTY_KEYWORD_CACHE_TTL_MS = 30_000

/** 首屏记忆条目：value 为空关键字响应的选项列表（模块级缓存不携带组件泛型，收窄为 unknown[]，读取侧断言回 T[]） */
interface EmptyKeywordCacheEntry {
  value: unknown[]
  /** 过期时间戳（毫秒）：读取时惰性比较，不设常驻清理定时器（过期条目随键 GC 一并回收） */
  expireAt: number
}

/**
 * 模块级缓存，以 fetcher 函数引用为键：
 * - 防串：不同 RemoteSelect 实例的 fetcher 引用不同 → 记忆天然隔离互不串台；
 *   同一 fetcher（父组件复用的同一池函数）跨实例共享 → 同源数据去重；
 * - 防泄漏：fetcher 多为父组件内联闭包，选 WeakMap 而非 Map——不强持键引用，
 *   组件销毁后闭包不可达时条目随之 GC，避免长期驻留内存。
 */
const emptyKeywordCache = new WeakMap<object, EmptyKeywordCacheEntry>()
</script>

<script setup lang="ts" generic="T">
/**
 * 远程搜索选择组件（契约 E：防抖 + 取消 + 三态 + 键盘可达）
 *
 * 职责：通用远程搜索单选/多选选择器。输入停顿 REMOTE_SEARCH_DEBOUNCE_MS(300ms) 后发请求，
 * 每次请求携带 AbortController——新输入立即取消旧请求，过期响应禁止回写状态（竞态防护）；
 * 下拉三态（加载 CircleNotch / 空结果 emptyText / 错误 errorText + 点击重试）；
 * 键盘可达（Enter 确认高亮、上下键移动、Esc 关闭、Tab 自然离焦）；
 * 多选选中项以 chip 形态展示在触发器内，chip 带 X 平级移除钮。
 * 关闭下拉不清空已选；打开即以空关键字拉一次首屏候选（PERF-24：空关键字首屏结果
 * 走模块级 30s 短 TTL 记忆，窗口内重复打开 0 重复请求；真关键字搜索始终实时）。
 *
 * 线程安全注意：全部状态为组件私有 ref；abortController/timer 单线程事件循环内
 * 顺序读写，无跨线程共享（浏览器环境无并发竞争）。
 */
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, useId, watch } from 'vue'
import { PhCheck, PhCircleNotch, PhMagnifyingGlass, PhX } from '@phosphor-icons/vue'

import { REMOTE_SEARCH_DEBOUNCE_MS } from '@/lib/constants'
import { cn } from '@/lib/utils'

const props = withDefaults(
  defineProps<{
    /** 选中值（单选 T | null；多选 T[]）——v-model 绑定，承载选项对象本身 */
    modelValue: T | T[] | null
    /** 选项 value 字段取值函数（如 (o) => o.id），用于去重/选中比对 */
    getValue: (option: T) => string | number
    /** 选项展示文本取值函数（如 (o) => o.name） */
    getLabel: (option: T) => string
    /** 远程搜索请求函数：入参关键字与取消信号，返回选项列表；组件负责防抖与取消 */
    fetcher: (keyword: string, signal: AbortSignal) => Promise<T[]>
    /** 占位提示（不做标签用途，标签由表单层 label 提供） */
    placeholder?: string
    /** 是否多选（多选 = true 时选中项以 chip 形态展示） */
    multiple?: boolean
    /** 初始已选选项回显数据（编辑态预填，避免只存对象无文案） */
    initialOptions?: T[]
    /** 空结果文案，缺省「无匹配项」 */
    emptyText?: string
    /** 失败重试文案，缺省「加载失败，点击重试」 */
    errorText?: string
    /** 禁用态 */
    disabled?: boolean
  }>(),
  {
    placeholder: '',
    multiple: false,
    initialOptions: () => [],
    emptyText: '无匹配项',
    errorText: '加载失败，点击重试',
    disabled: false,
  },
)

const emit = defineEmits<{
  /** 选中集变化（单选回抛选项对象或 null；多选回抛选项数组） */
  'update:modelValue': [value: T | T[] | null]
  /** 请求失败透出（页面决定 toast 展示） */
  error: [error: unknown]
}>()

/** 下拉开合 */
const open = ref(false)
/** 搜索关键字（输入即写入，防抖后才发请求） */
const keyword = ref('')
/** 当前下拉选项集（仅承载最近一次有效响应；shallowRef 规避泛型深解包） */
const options = shallowRef<T[]>([])
/** 加载中（防抖等待 + 请求在途均视为加载反馈） */
const loading = ref(false)
/** 请求失败标记（错误态 + 点击重试入口） */
const loadFailed = ref(false)
/** 键盘高亮项下标（-1 = 无高亮） */
const highlightIndex = ref(-1)
/** 在途请求控制器：新输入触发即 abort 旧请求 */
let controller: AbortController | null = null
/** 防抖定时器句柄 */
let debounceTimer: ReturnType<typeof setTimeout> | null = null

/** 选项缓存（value → 选项对象）：initialOptions 与历次响应累积，chip 回显不依赖当前下拉（shallowRef 规避泛型深解包） */
const optionCache = shallowRef(new Map<string | number, T>())

/** 多选判定（缺省单选） */
const isMultiple = computed(() => props.multiple === true)

/** 多选当前选中数组（单选时为空数组，不参与渲染） */
const selectedMulti = computed(() => (isMultiple.value ? ((props.modelValue ?? []) as T[]) : []))

/** 单选当前选中对象（多选时为 null） */
const selectedSingle = computed(() =>
  !isMultiple.value ? ((props.modelValue as T | null) ?? null) : null,
)

/** 多选 chip 数据源：以选中数组顺序渲染（对象在缓存中兜底文案） */
const chips = computed(() => selectedMulti.value)

/** 输入框展示值：有关键字显关键字；单选无关键字时回显选中项文案（多选由 chips 承载） */
const displayValue = computed(() => {
  if (isMultiple.value) return keyword.value
  if (keyword.value) return keyword.value
  return selectedSingle.value ? props.getLabel(selectedSingle.value) : ''
})

/** 下拉列表 DOM id（aria-controls / aria-activedescendant 关联） */
const listboxId = useId()

/** 选项 DOM id 生成（aria-activedescendant 指向高亮项） */
function optionId(index: number) {
  return `${listboxId}-opt-${index}`
}

/** 缓存选项（initialOptions 与响应结果统一入口） */
function cacheOptions(list: T[]) {
  const next = new Map(optionCache.value)
  for (const o of list) {
    next.set(props.getValue(o), o)
  }
  optionCache.value = next
}

/** 初始回显选项变化（编辑态异步加载完成）→ 入缓存 */
watch(
  () => props.initialOptions,
  (list) => {
    if (list && list.length > 0) cacheOptions(list)
  },
  { immediate: true },
)

/** 判定 AbortError（axios CanceledError / DOMException 均归一为取消语义） */
function isAbortError(err: unknown): boolean {
  return err instanceof Error && (err.name === 'AbortError' || err.name === 'CanceledError')
}

/**
 * 读取空关键字首屏记忆（PERF-24）：仅未过期才命中
 *
 * 过期采用惰性时间戳比较（读取时判 30s 窗口），无常驻定时器/侦听器，
 * 组件卸载无需清理缓存；过期条目不主动删除，等 fetcher 键被 GC 一并回收。
 *
 * @returns 命中返回首屏候选列表；未命中/已过期返回 null（触发真实请求）
 */
function readEmptyKeywordCache(): T[] | null {
  const entry = emptyKeywordCache.get(props.fetcher)
  if (!entry || Date.now() > entry.expireAt) return null
  return entry.value as T[]
}

/**
 * 执行一次搜索：abort 旧请求 → 新控制器发请求 → 过期响应禁止回写
 *
 * PERF-24：空关键字（首屏候选）优先读模块级 30s 短 TTL 记忆，命中直接回放
 * 跳过网络请求；真关键字（有内容）每次都实时搜索、永不读写缓存。
 *
 * @param kw 搜索关键字（空串 = 首屏候选）
 */
async function doSearch(kw: string) {
  controller?.abort()
  const current = new AbortController()
  controller = current
  loading.value = true
  loadFailed.value = false
  // 仅空关键字查记忆；命中则整个分支同步完成，无加载闪烁
  const remembered = kw.trim() === '' ? readEmptyKeywordCache() : null
  try {
    const list = remembered ?? (await props.fetcher(kw.trim(), current.signal))
    // 竞态防护：请求返回时控制器已被替换（新输入触发）→ 丢弃过期响应
    if (controller !== current) return
    options.value = list
    cacheOptions(list)
    highlightIndex.value = list.length > 0 ? 0 : -1
    // 仅「真实请求成功的空关键字结果」写入记忆：真关键字与记忆命中的回放不写，
    // 失败/被取消的结果也不写（下次打开仍重试真实接口）
    if (kw.trim() === '' && remembered === null) {
      emptyKeywordCache.set(props.fetcher, {
        value: list,
        expireAt: Date.now() + EMPTY_KEYWORD_CACHE_TTL_MS,
      })
    }
  } catch (err) {
    if (controller !== current || isAbortError(err)) return
    loadFailed.value = true
    emit('error', err)
  } finally {
    if (controller === current) {
      loading.value = false
    }
  }
}

/**
 * 防抖调度：新输入立即取消旧请求与旧定时器，300ms 后发新请求
 *
 * @param kw 搜索关键字
 */
function scheduleSearch(kw: string) {
  if (debounceTimer) clearTimeout(debounceTimer)
  controller?.abort()
  controller = null
  loading.value = true
  loadFailed.value = false
  debounceTimer = setTimeout(() => {
    debounceTimer = null
    void doSearch(kw)
  }, REMOTE_SEARCH_DEBOUNCE_MS)
}

/** 输入事件：写入关键字 + 打开下拉 + 防抖调度 */
function onInput(event: Event) {
  keyword.value = (event.target as HTMLInputElement).value
  if (!open.value) {
    open.value = true
  }
  scheduleSearch(keyword.value)
}

/** 打开下拉（首屏候选立即拉取，无防抖；PERF-24：空关键字在 doSearch 内先查 30s 记忆，命中不重复拉）；已打开时不重复拉 */
function openDropdown() {
  if (props.disabled || open.value) return
  open.value = true
  highlightIndex.value = -1
  void doSearch('')
}

/** 关闭下拉（不清空已选；中止在途请求并复位加载态；首屏短 TTL 记忆有意保留，供 30s 内重开去重） */
function closeDropdown() {
  open.value = false
  options.value = []
  highlightIndex.value = -1
  if (debounceTimer) {
    clearTimeout(debounceTimer)
    debounceTimer = null
  }
  controller?.abort()
  controller = null
  loading.value = false
  loadFailed.value = false
  keyword.value = ''
}

/** 选中一项：单选回抛对象并关闭；多选切换包含关系并保持打开 */
function selectOption(option: T) {
  if (isMultiple.value) {
    const value = props.getValue(option)
    const exists = selectedMulti.value.some((o) => props.getValue(o) === value)
    const next = exists
      ? selectedMulti.value.filter((o) => props.getValue(o) !== value)
      : [...selectedMulti.value, option]
    emit('update:modelValue', next)
  } else {
    emit('update:modelValue', option)
    closeDropdown()
  }
}

/** 移除多选 chip：回抛剔除后的数组 */
function removeChip(option: T) {
  const value = props.getValue(option)
  emit(
    'update:modelValue',
    selectedMulti.value.filter((o) => props.getValue(o) !== value),
  )
}

/** 单选清空（X 钮）：回抛 null */
function clearSingle() {
  if (props.disabled) return
  emit('update:modelValue', null)
}

/** 键盘导航：ArrowDown/ArrowUp 移动高亮（越界钳制），Enter 确认高亮项，Esc 关闭 */
function onKeydown(event: KeyboardEvent) {
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    if (!open.value) {
      openDropdown()
      return
    }
    const count = options.value.length
    if (count === 0) return
    const delta = event.key === 'ArrowDown' ? 1 : -1
    highlightIndex.value = (highlightIndex.value + delta + count) % count
  } else if (event.key === 'Enter') {
    event.preventDefault()
    if (open.value && highlightIndex.value >= 0 && options.value[highlightIndex.value]) {
      selectOption(options.value[highlightIndex.value])
    }
  } else if (event.key === 'Escape') {
    if (open.value) {
      event.stopPropagation()
      closeDropdown()
    }
  }
}

/** 错误态点击重试：立即以当前关键字重发（无防抖） */
function retry() {
  void doSearch(keyword.value)
}

/** 鼠标悬浮选项：同步键盘高亮下标（鼠标/键盘双通道一致） */
function hoverOption(index: number) {
  highlightIndex.value = index
}

/** 点击外部关闭下拉（document 级监听，根元素内点击不触发） */
function onDocumentMousedown(event: MouseEvent) {
  if (!rootRef.value?.contains(event.target as Node)) {
    closeDropdown()
  }
}

/** 组件根元素引用（点击外部判定锚点） */
const rootRef = ref<HTMLElement | null>(null)

onMounted(() => document.addEventListener('mousedown', onDocumentMousedown))
onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onDocumentMousedown)
  if (debounceTimer) clearTimeout(debounceTimer)
  controller?.abort()
})

/** 选项选中态判定（多选按 value 比对；单选当前下拉不承载回显） */
function isSelected(option: T): boolean {
  if (!isMultiple.value) return false
  const value = props.getValue(option)
  return selectedMulti.value.some((o) => props.getValue(o) === value)
}
</script>

<template>
  <div ref="rootRef" class="relative" data-testid="remote-select">
    <!-- 触发器：多选 chips 内联 + 输入框（role=combobox 承载键盘导航与 aria 展开态） -->
    <div
      :class="
        cn(
          'flex min-h-10 w-full flex-wrap items-center gap-1.5 rounded-xl border bg-surface px-3 py-1.5 transition-colors duration-150 focus-within:ring-2',
          props.disabled
            ? 'cursor-not-allowed border-border opacity-60'
            : 'border-border focus-within:border-brand focus-within:ring-brand/20',
        )
      "
      @mousedown.prevent="openDropdown()"
    >
      <!-- 多选 chips：展示文本 + X 平级移除钮（禁嵌套按钮） -->
      <span
        v-for="chip in chips"
        :key="props.getValue(chip)"
        :data-testid="`remote-chip-${props.getValue(chip)}`"
        class="inline-flex items-center gap-1 rounded-full bg-brand-soft px-2.5 py-0.5 text-xs font-medium text-brand-strong"
      >
        {{ props.getLabel(chip) }}
        <button
          type="button"
          :aria-label="`移除 ${props.getLabel(chip)}`"
          :data-testid="`remote-chip-remove-${props.getValue(chip)}`"
          :disabled="props.disabled"
          class="rounded-sm p-0.5 text-brand-strong/60 transition-colors duration-150 hover:text-danger focus-visible:outline-2 focus-visible:outline-brand"
          @mousedown.stop
          @click.stop="removeChip(chip)"
        >
          <PhX class="h-3 w-3" weight="bold" />
        </button>
      </span>
      <input
        type="text"
        :value="displayValue"
        :placeholder="props.placeholder"
        :disabled="props.disabled"
        :aria-expanded="open"
        aria-haspopup="listbox"
        role="combobox"
        :aria-controls="listboxId"
        :aria-activedescendant="open && highlightIndex >= 0 ? optionId(highlightIndex) : undefined"
        data-testid="remote-input"
        class="h-7 min-w-[80px] flex-1 bg-transparent text-sm text-text outline-none placeholder:text-text-subtle disabled:cursor-not-allowed"
        @input="onInput"
        @focus="openDropdown()"
        @keydown="onKeydown"
      />
      <!-- 单选清空钮：有选中且非禁用时出现 -->
      <button
        v-if="!isMultiple && selectedSingle && !props.disabled"
        type="button"
        aria-label="清除选择"
        data-testid="remote-clear"
        class="rounded-sm p-1 text-text-subtle transition-colors duration-150 hover:text-danger focus-visible:outline-2 focus-visible:outline-brand"
        @mousedown.stop
        @click.stop="clearSingle()"
      >
        <PhX class="h-3.5 w-3.5" weight="bold" />
      </button>
      <PhMagnifyingGlass
        aria-hidden="true"
        class="pointer-events-none h-4 w-4 shrink-0 text-text-subtle"
      />
    </div>

    <!-- 下拉面板：加载（CircleNotch）/ 错误（点击重试）/ 空（emptyText）/ 选项列表 -->
    <ul
      v-if="open"
      :id="listboxId"
      role="listbox"
      :aria-multiselectable="isMultiple || undefined"
      data-testid="remote-listbox"
      class="absolute z-30 mt-1 max-h-60 w-full overflow-auto rounded-xl border border-border bg-surface p-1 shadow-md"
    >
      <!-- 加载态：旋转指示 -->
      <li
        v-if="loading"
        data-testid="remote-loading"
        class="flex items-center gap-2 px-3 py-2.5 text-sm text-text-muted"
        role="presentation"
      >
        <PhCircleNotch class="h-4 w-4 animate-spin" />
        搜索中…
      </li>
      <!-- 错误态：errorText + 点击重试（新输入亦会自动重发） -->
      <li v-else-if="loadFailed" data-testid="remote-error" role="presentation">
        <button
          type="button"
          class="w-full rounded-lg px-3 py-2.5 text-left text-sm text-danger transition-colors duration-150 hover:bg-surface-2"
          @click="retry"
        >
          {{ props.errorText }}
        </button>
      </li>
      <!-- 空态：emptyText 引导 -->
      <li
        v-else-if="options.length === 0"
        data-testid="remote-empty"
        class="px-3 py-2.5 text-sm text-text-subtle"
        role="presentation"
      >
        {{ props.emptyText }}
      </li>
      <!-- 选项列表：role=option + aria-selected；键盘高亮与鼠标 hover 双通道 -->
      <template v-else>
        <li
          v-for="(option, index) in options"
          :id="optionId(index)"
          :key="props.getValue(option)"
          :data-testid="`remote-option-${props.getValue(option)}`"
          role="option"
          :aria-selected="isSelected(option)"
          :class="
            cn(
              'flex cursor-pointer items-center justify-between gap-2 rounded-lg px-3 py-2 text-sm transition-colors duration-150',
              index === highlightIndex ? 'bg-brand-soft text-brand-strong' : 'text-text',
              isSelected(option) && 'font-medium',
            )
          "
          @mouseenter="hoverOption(index)"
          @click="selectOption(option)"
        >
          <span class="min-w-0 truncate">{{ props.getLabel(option) }}</span>
          <!-- 多选选中指示（实心勾选底） -->
          <span
            v-if="isMultiple"
            class="flex h-4 w-4 shrink-0 items-center justify-center rounded-sm border transition-colors duration-150"
            :class="isSelected(option) ? 'border-brand bg-brand text-white' : 'border-border'"
          >
            <PhCheck v-if="isSelected(option)" class="h-3 w-3" weight="bold" />
          </span>
        </li>
      </template>
    </ul>
  </div>
</template>
