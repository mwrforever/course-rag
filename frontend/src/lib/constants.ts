/**
 * B 端业务常量单一事实源（契约 E.2 行为清单第 1 条新建）
 *
 * 职责：集中远程搜索防抖窗口与课程表单预置分类等跨页面复用常量，
 * 避免魔法数字散落各组件（宪法 A.2.1 前端同源要求：阈值配置化/常量集中）。
 */

/** 远程搜索防抖窗口（毫秒）：remote-select 输入停顿该时长后才发请求（契约 E 定值 300） */
export const REMOTE_SEARCH_DEBOUNCE_MS = 300

/**
 * 课程分类预置选项（课程表单分类下拉的 datalist 候选）
 *
 * 来源：既有表单占位文案「如 AI / LLM / RAG」扩展为常用教学分类；
 * 仅作下拉候选，允许输入自定义值（契约 T2.2：分类改预置选项下拉 + 自定义输入）。
 */
export const COURSE_CATEGORY_PRESETS = [
  'AI',
  'LLM',
  'RAG',
  '后端开发',
  '前端开发',
  '数据结构',
  '编程基础',
] as const

/** 封面上传支持的扩展名白名单（契约 D.2.1 course.cover.allowed-extensions 前端镜像） */
export const COVER_ALLOWED_EXTENSIONS = ['jpg', 'jpeg', 'png', 'webp'] as const

/** 封面上传大小上限（MB，契约 D.2.1 course.cover.max-size-mb 前端镜像） */
export const COVER_MAX_SIZE_MB = 5
