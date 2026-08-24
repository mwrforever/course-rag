<script setup lang="ts">
/**
 * 课程编辑页（设计 §2.4.4 课程编辑 + §2.6 表格/表单/弹窗规范）
 *
 * 路由复用：/courses/new（course-new）与 /courses/:id（course-detail）共用本组件。
 * 新建模式仅渲染基础信息表单，create 成功后跳转 /courses/{id} 进入完整编辑态
 * （两路由同组件，vue-router 复用实例，靠 fullPath watcher 触发加载）。
 *
 * 能力清单：
 * 1. 基础信息：封面 URL 输入 + 实时预览（onError 兜底占位，无上传接口 G11）/
 *    标题* zod 前置校验 / 简述 / 分类 / 讲师名 / 价格 / 课时 / 标签 chips /
 *    报名链接 / 状态（编辑态 ACTIVE/ARCHIVED）；新建 create → 跳转，编辑 update
 * 2. 内容 4 Tab：intro/syllabus/instructor/faq 按 sortOrder 排序渲染，
 *    md-editor-v3 编辑，逐 Tab 独立保存（PUT /contents/{contentType} 裸 JSON 字符串 body）
 * 3. 排期 Section：表格（起止/类型/地点/讲师/容量/已报）+ 新增 Dialog +
 *    行内编辑 Dialog + 删除二次确认（全部提交期 Esc/遮罩/取消拦截）
 * 4. 教师分配：双栏（已分配 / 可选=全量 TEACHER 剔除已分配 + 搜索过滤）+
 *    POST [ids] 分配 + 移除 DELETE 带 body（axios data 写法，设计 §2.4.4）
 * 5. 学生名单：已选列表（username/displayName/enrolledAt）+ 添加 Dialog
 *    （搜索多选 → POST 返回成功数 → 「成功添加 N 名」提示）+ 行移除二次确认
 * 6. 四态：loading 骨架 / error 横幅重试 / 正常（新建模式无 loading）
 *
 * 契约要点：id/learningCount 为 Long 字符串铁律；价格/课时/人数数字域 tabular-nums；
 * 教师用户池经 GET /users?role=TEACHER 一次拉取（后端无 keyword 参数，搜索客户端过滤，
 * 选择器只列 TEACHER 角色兜底 R18）；学生候选走 role=STUDENT。
 *
 * 线程安全注意：全部状态为组件私有 ref/reactive，无跨实例共享可变状态。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { MdEditor } from 'md-editor-v3'
import 'md-editor-v3/lib/style.css'
import { z } from 'zod'
import {
  PhArrowLeft,
  PhCheck,
  PhImageSquare,
  PhPencilSimple,
  PhPlus,
  PhSpinnerGap,
  PhTrash,
  PhUserPlus,
  PhWarningCircle,
  PhX,
} from '@phosphor-icons/vue'

import { Button } from '@/components/ui/button'
import { ApiError, courseApi, enrollmentApi, scheduleApi, userApi } from '@/lib/api'
import { showToast } from '@/lib/toast'
import { formatDateTime } from '@/lib/utils'

import type {
  CourseContentDTO,
  CourseDTO,
  CourseScheduleVO,
  StudentDTO,
  UpdateCourseRequest,
  UserDTO,
} from '@/lib/types'

// ====================================================================
// 基础信息表单（新建/编辑共用；标题必填 zod）
// ====================================================================

/** 标题必填校验（设计 §2.4.4：标题*，错误红字 input 下方） */
const titleSchema = z.object({ title: z.string().min(1, '请输入课程标题') })

/** 基础表单承载：价格/容量以字符串承载，提交时数值化（避免输入过程 Number 精度抖动） */
const form = reactive({
  title: '',
  description: '',
  coverImage: '',
  category: '',
  instructorName: '',
  price: '',
  duration: '',
  enrollmentLink: '',
  status: 'ACTIVE' as 'ACTIVE' | 'ARCHIVED',
})

/** 标签 chips：回车添加、X 删除（提交体为字符串数组） */
const tags = ref<string[]>([])
const tagInput = ref('')
/** 标题就地错误（zod 校验失败展示，校验通过清空） */
const fieldError = ref('')
/** 基础信息保存中（提交期按钮禁用 + 文案切换） */
const saving = ref(false)

/** 封面图加载失败标记：onError 兜底切占位（无上传接口 G11），URL 变更自动复位 */
const coverBroken = ref(false)

/**
 * 标签回车添加：trim 后去重入列，输入框清空（测试/交互同契约）
 *
 * @param evt 键盘事件（enter 由模板 .prevent 拦截表单提交）
 */
function addTag() {
  const value = tagInput.value.trim()
  if (value && !tags.value.includes(value)) {
    tags.value.push(value)
  }
  tagInput.value = ''
}

/**
 * 删除标签 chip
 *
 * @param tag 待删除标签文本（不存在时静默忽略）
 */
function removeTag(tag: string) {
  tags.value = tags.value.filter((t) => t !== tag)
}

/**
 * 基础信息保存：zod 前置校验（失败就地报错不发请求）→ 新建/编辑分派
 *
 * 新建：create（CreateCourseRequest 不含 status，新建默认 ACTIVE）→ toast →
 * 跳转 /courses/{id} 继续编辑；编辑：update（UpdateCourseRequest 全字段含 status）。
 * 价格字符串数值化：空值发 undefined（axios 序列化自动省略），非空 Number() 精确转换。
 */
async function saveBasic() {
  const parsed = titleSchema.safeParse({ title: form.title })
  if (!parsed.success) {
    fieldError.value = parsed.error.issues[0]?.message ?? '请输入课程标题'
    return
  }
  fieldError.value = ''
  saving.value = true
  try {
    const common = {
      title: form.title,
      description: form.description,
      coverImage: form.coverImage,
      category: form.category,
      instructorName: form.instructorName,
      price: form.price === '' ? undefined : Number(form.price),
      duration: form.duration,
      tags: tags.value.length > 0 ? tags.value : null,
      enrollmentLink: form.enrollmentLink,
    }
    if (isNew.value) {
      const created = await courseApi.create(common)
      showToast('课程创建成功', 'success')
      await router.push({ name: 'course-detail', params: { id: created.id } })
    } else {
      const payload: UpdateCourseRequest = { ...common, status: form.status }
      await courseApi.update(courseId.value, payload)
      showToast('课程信息已保存', 'success')
    }
  } catch (err) {
    showToast(messageOf(err, '保存失败，请稍后重试'), 'danger')
  } finally {
    saving.value = false
  }
}

// ====================================================================
// 路由模式（新建 /courses/new 与编辑 /courses/:id 复用同组件）
// ====================================================================

const route = useRoute()
const router = useRouter()

/** 新建模式：路由名 course-new（编辑模式 course-detail 带 :id） */
const isNew = computed(() => route.name === 'course-new')

/** 课程 id：编辑模式取自路由参数（Long 字符串铁律） */
const courseId = computed(() => String(route.params.id ?? ''))

/** 返回课程列表 */
function goBackToList() {
  router.push({ name: 'courses' })
}

// ====================================================================
// 编辑模式加载（页面级四态：loading 骨架 / error 横幅重试 / 正常）
// ====================================================================

/** 页面对象：课程主数据（teacherIds 驱动教师双栏） */
const course = ref<CourseDTO | null>(null)
/** 排期列表（排期 Section 表格） */
const schedules = ref<CourseScheduleVO[]>([])
/** 学生名单（已报名学生行） */
const students = ref<StudentDTO[]>([])
/** 教师候选池：GET /users?role=TEACHER 一次拉取，后续客户端过滤（无 keyword 参数） */
const teacherPool = ref<UserDTO[]>([])

const loading = ref(!isNew.value)
const error = ref('')

/**
 * 接口错误分级文案（与列表页 messageOf 同构）
 *
 * @param err 捕获异常：ApiError 透出 message（503 统一降级文案）；未知异常兜底
 * @param fallback 非 ApiError 时的操作级兜底文案
 * @returns 展示文案
 */
function messageOf(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    return err.code === 503 ? '服务暂时不可用，请稍后重试' : err.message
  }
  return fallback
}

/** 课程回填基础表单（价格/容量数值 → 字符串承载；标签数组化；封面错误态复位） */
function applyCourseToForm(c: CourseDTO) {
  form.title = c.title
  form.description = c.description ?? ''
  form.coverImage = c.coverImage ?? ''
  form.category = c.category ?? ''
  form.instructorName = c.instructorName ?? ''
  form.price = c.price === 0 ? '' : String(c.price)
  form.duration = c.duration ?? ''
  form.enrollmentLink = c.enrollmentLink ?? ''
  form.status = c.status === 'ARCHIVED' ? 'ARCHIVED' : 'ACTIVE'
  tags.value = [...(c.tags ?? [])]
  coverBroken.value = false
}

/**
 * 页面级加载：课程 + 排期 + 学生名单 + 教师候选池 并发拉取（单点失败整体进入错误态）
 *
 * 内容 4 Tab 独立加载（loadContents），互不影响（内容失败仅 Tab 区横幅，设计 §2.4.4）。
 */
async function loadPage() {
  if (isNew.value) return
  loading.value = true
  error.value = ''
  try {
    const [c, scheduleList, studentList, teacherPage] = await Promise.all([
      courseApi.get(courseId.value),
      scheduleApi.listByCourse(courseId.value),
      enrollmentApi.students(courseId.value),
      userApi.list({ role: 'TEACHER', size: 100 }),
    ])
    course.value = c
    applyCourseToForm(c)
    schedules.value = scheduleList ?? []
    students.value = studentList ?? []
    // 教师池按角色客户端兜底过滤（设计 R18：后端不校验，选择器只列 TEACHER）
    teacherPool.value = (teacherPage.records ?? []).filter((u) => u.role === 'TEACHER')
  } catch (err) {
    error.value = messageOf(err, '课程加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

/** 教师分配变更后轻刷新：仅重拉课程（teacherIds 驱动双栏重排），不清空未保存表单 */
async function refreshCourse() {
  try {
    course.value = await courseApi.get(courseId.value)
  } catch (err) {
    showToast(messageOf(err, '课程刷新失败，请稍后重试'), 'danger')
  }
}

// ====================================================================
// 内容 4 Tab（intro/syllabus/instructor/faq，md-editor-v3 逐 Tab 独立保存）
// ====================================================================

/** 四 Tab 常量表：type 与后端 contentType 一致，label 为界面文案 */
const CANONICAL_TABS: { type: string; label: string; sort: number }[] = [
  { type: 'intro', label: '课程介绍', sort: 1 },
  { type: 'syllabus', label: '教学大纲', sort: 2 },
  { type: 'instructor', label: '讲师信息', sort: 3 },
  { type: 'faq', label: '常见问题', sort: 4 },
]

/** 各 Tab 独立保存成功 toast（逐 Tab 独立保存，文案区分） */
const CONTENT_SAVED_TOAST: Record<string, string> = {
  intro: '课程介绍已保存',
  syllabus: '教学大纲已保存',
  instructor: '讲师信息已保存',
  faq: '常见问题已保存',
}

/** Tab 渲染顺序：按后端 sortOrder；后端缺失时回退常量表 */
const tabOrder = ref<{ type: string; label: string }[]>([])
/** 正文缓存：contentType → markdown 正文（Tab 切换互不串写） */
const contentMap = ref<Record<string, string>>({})
/** 当前激活 Tab */
const activeTab = ref('intro')
const contentsLoading = ref(false)
const contentsError = ref('')
const contentSaving = ref(false)

/** 当前激活 Tab 的正文（编辑器 modelValue 输入源） */
const activeContent = computed(() => contentMap.value[activeTab.value] ?? '')

/**
 * 内容加载：按 sortOrder 排序建索引（缺失 body 兜底空串）
 *
 * @returns Promise 无返回值；失败时 contentsError 横幅（重试按钮 retry-contents）
 */
async function loadContents() {
  if (isNew.value) return
  contentsLoading.value = true
  contentsError.value = ''
  try {
    const list: CourseContentDTO[] = (await courseApi.contents(courseId.value)) ?? []
    const map: Record<string, string> = {}
    for (const item of list) {
      map[item.contentType] = item.content ?? ''
    }
    contentMap.value = map
    // Tab 顺序按后端 sortOrder；空列表回退常量表（四个 Tab 均可直接开写）
    tabOrder.value =
      list.length > 0
        ? list
            .slice()
            .sort((a, b) => a.sortOrder - b.sortOrder)
            .map((item) => ({ type: item.contentType, label: labelOf(item.contentType) }))
        : CANONICAL_TABS.map((t) => ({ type: t.type, label: t.label }))
    activeTab.value = tabOrder.value[0]?.type ?? 'intro'
  } catch (err) {
    contentsError.value = messageOf(err, '内容加载失败，请稍后重试')
  } finally {
    contentsLoading.value = false
  }
}

/** contentType → Tab 文案（未知类型以原值兜底展示） */
function labelOf(type: string): string {
  return CANONICAL_TABS.find((t) => t.type === type)?.label ?? type
}

/** 编辑器输入回写当前 Tab（正文缓存按 contentType 分键，切 Tab 不丢未保存内容） */
function handleContentEdit(value: string) {
  contentMap.value[activeTab.value] = value
}

/**
 * 逐 Tab 独立保存：PUT /{id}/contents/{contentType}，body 为裸 JSON 字符串
 * （api 层显式 Content-Type: application/json，axios 字符串 data 原样透传）
 */
async function saveContent() {
  if (contentsError.value) return
  const type = activeTab.value
  contentSaving.value = true
  try {
    await courseApi.updateContent(courseId.value, type, contentMap.value[type] ?? '')
    showToast(CONTENT_SAVED_TOAST[type] ?? '内容已保存', 'success')
  } catch (err) {
    showToast(messageOf(err, '内容保存失败，请稍后重试'), 'danger')
  } finally {
    contentSaving.value = false
  }
}

// ====================================================================
// 排期 Section（新增 Dialog / 行内编辑 Dialog / 删除二次确认）
// ====================================================================

/** 排期表单承载：capacity 字符串承载，提交时数值化（可为空） */
const scheduleForm = reactive({
  startDate: '',
  endDate: '',
  scheduleType: 'ONLINE',
  location: '',
  instructorName: '',
  capacity: '',
})
/** 排期 Dialog 打开态；scheduleEditing 非 null 表示编辑（标题「编辑排期」） */
const scheduleDialogOpen = ref(false)
const scheduleEditing = ref<CourseScheduleVO | null>(null)
const scheduleError = ref('')
const scheduleSubmitting = ref(false)
/** 删除确认：非 null 展示二次确认 Dialog */
const scheduleDeleting = ref<CourseScheduleVO | null>(null)
const scheduleDeletingLoading = ref(false)

/**
 * 打开新增排期 Dialog：清空表单与错误，编辑态置 null
 */
function openCreateSchedule() {
  scheduleEditing.value = null
  scheduleForm.startDate = ''
  scheduleForm.endDate = ''
  scheduleForm.scheduleType = 'ONLINE'
  scheduleForm.location = ''
  scheduleForm.instructorName = ''
  scheduleForm.capacity = ''
  scheduleError.value = ''
  scheduleDialogOpen.value = true
}

/**
 * 打开行内编辑 Dialog：行数据回填（capacity 数值 → 字符串）
 *
 * @param s 待编辑排期行
 */
function openEditSchedule(s: CourseScheduleVO) {
  scheduleEditing.value = s
  scheduleForm.startDate = s.startDate
  scheduleForm.endDate = s.endDate
  scheduleForm.scheduleType = s.scheduleType
  scheduleForm.location = s.location ?? ''
  scheduleForm.instructorName = s.instructorName ?? ''
  scheduleForm.capacity = s.capacity === 0 ? '' : String(s.capacity)
  scheduleError.value = ''
  scheduleDialogOpen.value = true
}

/** 关闭排期 Dialog：提交期间拦截（防误关丢提交态） */
function closeScheduleDialog() {
  if (scheduleSubmitting.value) return
  scheduleDialogOpen.value = false
}

/**
 * 排期表单校验：起止日期必填（就地报错不发请求）
 *
 * @returns 是否通过校验
 */
function validateSchedule(): boolean {
  if (!scheduleForm.startDate) {
    scheduleError.value = '请输入开始日期'
    return false
  }
  if (!scheduleForm.endDate) {
    scheduleError.value = '请输入结束日期'
    return false
  }
  scheduleError.value = ''
  return true
}

/**
 * 排期保存：新增 create（CreateScheduleRequest）/ 编辑 update（全字段）→
 * toast → 关闭 Dialog → 刷新排期表格
 */
async function submitSchedule() {
  if (!validateSchedule()) return
  scheduleSubmitting.value = true
  try {
    const capacity = scheduleForm.capacity === '' ? undefined : Number(scheduleForm.capacity)
    if (scheduleEditing.value) {
      await scheduleApi.update(scheduleEditing.value.id, {
        startDate: scheduleForm.startDate,
        endDate: scheduleForm.endDate,
        scheduleType: scheduleForm.scheduleType,
        location: scheduleForm.location,
        instructorName: scheduleForm.instructorName,
        capacity,
      })
    } else {
      await scheduleApi.create(courseId.value, {
        startDate: scheduleForm.startDate,
        endDate: scheduleForm.endDate,
        scheduleType: scheduleForm.scheduleType,
        location: scheduleForm.location,
        instructorName: scheduleForm.instructorName,
        capacity,
      })
    }
    showToast('排期已保存', 'success')
    scheduleDialogOpen.value = false
    await loadSchedules()
  } catch (err) {
    showToast(messageOf(err, '排期保存失败，请稍后重试'), 'danger')
  } finally {
    scheduleSubmitting.value = false
  }
}

/** 刷新排期表格（新增/编辑/删除后共用） */
async function loadSchedules() {
  try {
    schedules.value = (await scheduleApi.listByCourse(courseId.value)) ?? []
  } catch (err) {
    showToast(messageOf(err, '排期加载失败，请稍后重试'), 'danger')
  }
}

/** 打开删除确认 Dialog */
function requestDeleteSchedule(s: CourseScheduleVO) {
  scheduleDeleting.value = s
}

/** 取消删除确认（提交期间拦截） */
function cancelDeleteSchedule() {
  if (scheduleDeletingLoading.value) return
  scheduleDeleting.value = null
}

/** 确认删除排期：remove → toast → 关闭确认框 → 刷新表格 */
async function confirmDeleteSchedule() {
  if (!scheduleDeleting.value) return
  scheduleDeletingLoading.value = true
  try {
    await scheduleApi.remove(scheduleDeleting.value.id)
    showToast('排期已删除', 'success')
    scheduleDeleting.value = null
    await loadSchedules()
  } catch (err) {
    showToast(messageOf(err, '排期删除失败，请稍后重试'), 'danger')
  } finally {
    scheduleDeletingLoading.value = false
  }
}

// ====================================================================
// 教师分配双栏（可选 = 全量 TEACHER 剔除已分配 + 搜索过滤；POST/DELETE 数组 body）
// ====================================================================

/** 待分配勾选的教师 id（复选框 v-model 数组） */
const teacherSelected = ref<string[]>([])
const teacherSearch = ref('')
const teacherAssigning = ref(false)
const teacherRemovingId = ref('')

/** 已分配教师 id（课程 teacherIds，Long 字符串铁律） */
const assignedTeacherIds = computed(() => course.value?.teacherIds ?? [])

/**
 * 可选教师过滤：角色 TEACHER 兜底（R18）＋ 剔除已分配 ＋ 搜索关键词
 * （displayName/username 子串命中，后端 /users 无 keyword 参数）
 *
 * @returns 过滤后的可选教师列表
 */
const availableTeachers = computed(() =>
  teacherPool.value.filter(
    (t) =>
      t.role === 'TEACHER' &&
      !assignedTeacherIds.value.includes(t.id) &&
      (teacherSearch.value.trim() === '' ||
        t.displayName.includes(teacherSearch.value.trim()) ||
        t.username.includes(teacherSearch.value.trim())),
  ),
)

/** 已分配教师明细：id → 用户对象（候选池不在场时以 id 兜底展示） */
const assignedTeachers = computed(() =>
  assignedTeacherIds.value
    .map((id) => teacherPool.value.find((t) => t.id === id))
    .filter((t): t is UserDTO => Boolean(t)),
)

/**
 * 分配所选教师：POST /{id}/teachers 数组 body → toast → 清空勾选 → 重拉课程刷新双栏
 */
async function assignTeachers() {
  if (teacherSelected.value.length === 0) return
  teacherAssigning.value = true
  try {
    await courseApi.addTeachers(courseId.value, teacherSelected.value)
    showToast('教师分配成功', 'success')
    teacherSelected.value = []
    await refreshCourse()
  } catch (err) {
    showToast(messageOf(err, '教师分配失败，请稍后重试'), 'danger')
  } finally {
    teacherAssigning.value = false
  }
}

/**
 * 移除教师：DELETE /{id}/teachers 带 body [id]（axios data 写法）→ toast → 重拉课程
 *
 * @param t 待移除教师（行内移除按钮）
 */
async function removeTeacher(t: UserDTO) {
  teacherRemovingId.value = t.id
  try {
    await courseApi.removeTeachers(courseId.value, [t.id])
    showToast('已移除教师', 'success')
    await refreshCourse()
  } catch (err) {
    showToast(messageOf(err, '移除教师失败，请稍后重试'), 'danger')
  } finally {
    teacherRemovingId.value = ''
  }
}

// ====================================================================
// 学生名单（添加 Dialog：搜索多选 → POST 成功数提示；行移除二次确认）
// ====================================================================

/** 已报名学生 id 集合（候选过滤剔除已报名） */
const enrolledIds = computed(() => new Set(students.value.map((s) => s.id)))

const studentDialogOpen = ref(false)
const studentCandidates = ref<UserDTO[]>([])
const studentSearch = ref('')
const studentSelected = ref<string[]>([])
const studentSubmitting = ref(false)
const studentCandidatesLoading = ref(false)
/** 待移除学生：非 null 时展示二次确认 Dialog */
const studentDeleting = ref<StudentDTO | null>(null)
const studentDeletingLoading = ref(false)

/**
 * 打开添加学生 Dialog：清空勾选与搜索，拉取 STUDENT 角色候选
 * （后端 /users 无 keyword 参数，搜索客户端过滤；教师端创建的学生由后端约束）
 */
async function openStudentDialog() {
  studentDialogOpen.value = true
  studentSearch.value = ''
  studentSelected.value = []
  studentCandidatesLoading.value = true
  try {
    const res = await userApi.list({ role: 'STUDENT', size: 100 })
    studentCandidates.value = (res.records ?? []).filter((u) => u.role === 'STUDENT')
  } catch (err) {
    showToast(messageOf(err, '学生列表加载失败，请稍后重试'), 'danger')
  } finally {
    studentCandidatesLoading.value = false
  }
}

/**
 * 学生候选过滤：角色 STUDENT 兜底 ＋ 剔除已报名 ＋ 搜索关键词（displayName/username）
 *
 * @returns 过滤后的可选学生列表
 */
const studentOptions = computed(() => {
  const kw = studentSearch.value.trim()
  return studentCandidates.value.filter(
    (u) =>
      !enrolledIds.value.has(u.id) &&
      (kw === '' || u.displayName.includes(kw) || u.username.includes(kw)),
  )
})

/** 候选行点击切换勾选（多选追加，再次点击取消） */
function toggleStudent(u: UserDTO) {
  if (studentSelected.value.includes(u.id)) {
    studentSelected.value = studentSelected.value.filter((id) => id !== u.id)
  } else {
    studentSelected.value.push(u.id)
  }
}

/** 关闭添加学生 Dialog：提交期间拦截（防误关丢提交态） */
function closeStudentDialog() {
  if (studentSubmitting.value) return
  studentDialogOpen.value = false
}

/**
 * 提交批量添加：POST /{id}/students {studentIds} → 以返回成功数提示
 * （后端返回 Integer 成功数，可能部分成功，如选中 2 名成功 1 名 → 「成功添加 1 名」）
 */
async function submitStudents() {
  if (studentSelected.value.length === 0) return
  studentSubmitting.value = true
  try {
    const added = await enrollmentApi.addStudents(courseId.value, {
      studentIds: studentSelected.value,
    })
    showToast(`成功添加 ${added} 名`, 'success')
    studentDialogOpen.value = false
    await loadStudents()
  } catch (err) {
    showToast(messageOf(err, '添加学生失败，请稍后重试'), 'danger')
  } finally {
    studentSubmitting.value = false
  }
}

/** 刷新学生名单（添加/移除后共用） */
async function loadStudents() {
  try {
    students.value = (await enrollmentApi.students(courseId.value)) ?? []
  } catch (err) {
    showToast(messageOf(err, '学生名单加载失败，请稍后重试'), 'danger')
  }
}

/** 打开移除学生二次确认 */
function requestDeleteStudent(s: StudentDTO) {
  studentDeleting.value = s
}

/** 取消移除确认（提交期间拦截） */
function cancelDeleteStudent() {
  if (studentDeletingLoading.value) return
  studentDeleting.value = null
}

/** 确认移除学生：removeStudent → toast → 关闭确认框 → 刷新名单 */
async function confirmDeleteStudent() {
  if (!studentDeleting.value) return
  studentDeletingLoading.value = true
  try {
    await enrollmentApi.removeStudent(courseId.value, studentDeleting.value.id)
    showToast('已移除学生', 'success')
    studentDeleting.value = null
    await loadStudents()
  } catch (err) {
    showToast(messageOf(err, '移除学生失败，请稍后重试'), 'danger')
  } finally {
    studentDeletingLoading.value = false
  }
}

// ====================================================================
// 生命周期：编辑模式加载 + 同组件路由复用（new → detail 切换触发加载）
// ====================================================================

/** 封面 URL 变更：错误预览态复位（onError 兜底后改 URL 恢复预览） */
watch(
  () => form.coverImage,
  () => {
    coverBroken.value = false
  },
)

/**
 * 同组件路由复用监听：/courses/new 创建成功 push /courses/{id} 与编辑间 id 切换均触发重载
 * （两路由解析到同一组件，vue-router 复用实例不重挂载，onMounted 不会二次执行）
 */
watch(
  () => route.fullPath,
  () => {
    if (isNew.value) return
    void loadPage()
    void loadContents()
  },
)

/** 首次挂载：编辑模式加载页面级数据 + 内容 4 Tab（新建模式零请求） */
onMounted(() => {
  if (isNew.value) return
  void loadPage()
  void loadContents()
})
</script>

<template>
  <main class="mx-auto max-w-[1400px] px-8 py-6">
    <!-- 页头操作行：返回列表 + 页眉说明 -->
    <div class="mb-4 flex items-center justify-between">
      <Button variant="ghost" size="sm" data-testid="back-to-courses" @click="goBackToList">
        <PhArrowLeft class="h-4 w-4" />
        返回课程列表
      </Button>
      <p v-if="isNew" class="text-sm text-text-muted">
        保存后将进入完整编辑页，可配置内容、排期与学员
      </p>
    </div>

    <!-- 页面级错误态：横幅 + 重试（四态规范，编辑模式加载失败） -->
    <div
      v-if="error"
      role="alert"
      class="flex items-center justify-between gap-4 rounded-lg border border-danger/30 bg-red-50 px-4 py-3"
    >
      <span class="text-sm text-danger">{{ error }}</span>
      <Button variant="outline" size="sm" data-testid="retry-course" @click="loadPage">重试</Button>
    </div>

    <!-- 加载态：骨架屏与最终布局同形（页面级四态） -->
    <div v-else-if="loading" data-testid="edit-skeleton" class="space-y-6" aria-label="课程加载中">
      <div class="rounded-xl border border-border bg-surface">
        <div class="h-14 animate-pulse border-b border-border bg-slate-50" />
        <div class="grid grid-cols-2 gap-6 p-6">
          <div v-for="i in 6" :key="`form-${i}`" class="h-10 animate-pulse rounded bg-slate-100" />
        </div>
      </div>
      <div class="grid grid-cols-3 gap-6">
        <div class="col-span-2 h-64 animate-pulse rounded-xl border border-border bg-slate-100" />
        <div class="h-64 animate-pulse rounded-xl border border-border bg-slate-100" />
      </div>
    </div>

    <!-- 正常态：基础表单 + 编辑模式各 Section -->
    <template v-else>
      <!-- ================================================================
           基础信息表单（标题* zod；封面 URL 实时预览；标签 chips；状态仅编辑态）
           ================================================================ -->
      <section class="rounded-xl border border-border bg-surface p-6">
        <h2 class="text-base font-semibold text-text">基础信息</h2>
        <div class="mt-5 grid grid-cols-2 gap-x-6 gap-y-4">
          <!-- 封面 URL + 实时预览（无上传接口 G11，onError 兜底占位） -->
          <div class="col-span-2">
            <label for="course-cover-url" class="mb-1.5 block text-sm font-medium text-text">
              封面图 URL
            </label>
            <div class="flex items-start gap-4">
              <input
                id="course-cover-url"
                v-model="form.coverImage"
                type="text"
                data-testid="field-cover"
                aria-label="封面图 URL"
                placeholder="https://cdn.example.com/cover.jpg（无上传接口，直接填图片地址）"
                class="h-10 w-full max-w-[520px] rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
              />
              <!-- 预览图：加载失败切占位（cover-fallback），URL 变更自动复位 -->
              <img
                v-if="form.coverImage && !coverBroken"
                :key="form.coverImage"
                data-testid="cover-preview"
                :src="form.coverImage"
                alt="封面预览"
                class="h-16 w-28 shrink-0 rounded-lg border border-border bg-surface-2 object-cover"
                @error="coverBroken = true"
              />
              <div
                v-else-if="form.coverImage && coverBroken"
                data-testid="cover-fallback"
                class="flex h-16 w-28 shrink-0 flex-col items-center justify-center gap-1 rounded-lg border border-border bg-surface-2 text-text-subtle"
              >
                <PhImageSquare class="h-5 w-5" />
                <span class="text-xs">封面预览</span>
              </div>
            </div>
          </div>

          <!-- 标题*：zod 前置校验，错误红字 input 下方 -->
          <div>
            <label for="course-title" class="mb-1.5 block text-sm font-medium text-text">
              标题 <span class="text-danger">*</span>
            </label>
            <input
              id="course-title"
              v-model="form.title"
              type="text"
              data-testid="field-title"
              aria-label="课程标题"
              placeholder="请输入课程标题"
              class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
            />
            <p v-if="fieldError" data-testid="field-error" class="mt-1 text-xs text-danger">
              {{ fieldError }}
            </p>
          </div>

          <!-- 分类 -->
          <div>
            <label for="course-category" class="mb-1.5 block text-sm font-medium text-text"
              >分类</label
            >
            <input
              id="course-category"
              v-model="form.category"
              type="text"
              data-testid="field-category"
              aria-label="课程分类"
              placeholder="如 AI / LLM / RAG"
              class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
            />
          </div>

          <!-- 简述 -->
          <div class="col-span-2">
            <label for="course-description" class="mb-1.5 block text-sm font-medium text-text"
              >简述</label
            >
            <textarea
              id="course-description"
              v-model="form.description"
              rows="2"
              data-testid="field-description"
              aria-label="课程简述"
              placeholder="一句话介绍课程内容"
              class="w-full resize-none rounded-lg border border-border bg-surface px-3 py-2 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
            />
          </div>

          <!-- 讲师名 -->
          <div>
            <label for="course-instructor" class="mb-1.5 block text-sm font-medium text-text"
              >讲师名</label
            >
            <input
              id="course-instructor"
              v-model="form.instructorName"
              type="text"
              data-testid="field-instructor"
              aria-label="讲师名"
              placeholder="主讲老师姓名"
              class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
            />
          </div>

          <!-- 价格（数字域 tabular-nums，提交时数值化） -->
          <div>
            <label for="course-price" class="mb-1.5 block text-sm font-medium text-text"
              >价格（元）</label
            >
            <input
              id="course-price"
              v-model="form.price"
              type="number"
              min="0"
              step="0.01"
              data-testid="field-price"
              aria-label="课程价格"
              placeholder="如 199"
              class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm tabular-nums text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
            />
          </div>

          <!-- 课时 -->
          <div>
            <label for="course-duration" class="mb-1.5 block text-sm font-medium text-text"
              >课时</label
            >
            <input
              id="course-duration"
              v-model="form.duration"
              type="text"
              data-testid="field-duration"
              aria-label="课时"
              placeholder="如 8 课时"
              class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
            />
          </div>

          <!-- 状态（仅编辑态；新建默认 ACTIVE 由后端落库） -->
          <div v-if="!isNew">
            <label for="course-status" class="mb-1.5 block text-sm font-medium text-text"
              >状态</label
            >
            <select
              id="course-status"
              v-model="form.status"
              data-testid="field-status"
              aria-label="课程状态"
              class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 focus:border-brand focus:ring-2 focus:ring-brand/20"
            >
              <option value="ACTIVE">ACTIVE（上架）</option>
              <option value="ARCHIVED">ARCHIVED（归档）</option>
            </select>
          </div>
          <div v-else>
            <p class="mb-1.5 text-sm font-medium text-text">状态</p>
            <p class="rounded-lg bg-surface-2 px-3 py-2.5 text-xs text-text-muted">
              新建课程默认 ACTIVE，保存后可调整
            </p>
          </div>

          <!-- 报名链接 -->
          <div class="col-span-2">
            <label for="course-link" class="mb-1.5 block text-sm font-medium text-text"
              >报名链接</label
            >
            <input
              id="course-link"
              v-model="form.enrollmentLink"
              type="text"
              data-testid="field-enrollment-link"
              aria-label="报名链接"
              placeholder="https://apply.example.com/xxx（可选）"
              class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
            />
          </div>

          <!-- 标签 chips：回车添加 + X 删除 -->
          <div class="col-span-2">
            <label for="course-tags" class="mb-1.5 block text-sm font-medium text-text">标签</label>
            <div
              class="flex min-h-10 flex-wrap items-center gap-2 rounded-lg border border-border bg-surface px-3 py-1.5"
            >
              <span
                v-for="tag in tags"
                :key="tag"
                :data-testid="`tag-chip-${tag}`"
                class="inline-flex items-center gap-1 rounded-full bg-brand-soft px-2.5 py-0.5 text-xs font-medium text-brand-strong"
              >
                {{ tag }}
                <button
                  type="button"
                  :data-testid="`tag-remove-${tag}`"
                  aria-label="移除标签"
                  class="text-brand-strong/60 transition-colors duration-150 hover:text-danger"
                  @click="removeTag(tag)"
                >
                  <PhX class="h-3 w-3" weight="bold" />
                </button>
              </span>
              <input
                v-model="tagInput"
                type="text"
                data-testid="tag-input"
                aria-label="标签输入"
                placeholder="输入后回车添加"
                class="h-7 min-w-[120px] flex-1 bg-transparent text-sm text-text outline-none placeholder:text-text-subtle"
                @keydown.enter.prevent="addTag"
              />
            </div>
          </div>
        </div>

        <!-- 保存行：新建 create / 编辑 update（提交期禁用 + spinner） -->
        <div class="mt-6 flex justify-end border-t border-border pt-4">
          <Button data-testid="save-basic" :disabled="saving" @click="saveBasic">
            <PhSpinnerGap v-if="saving" class="h-4 w-4 animate-spin" />
            {{ saving ? (isNew ? '创建中' : '保存中') : isNew ? '创建课程' : '保存基础信息' }}
          </Button>
        </div>
      </section>

      <!-- ================================================================
           编辑模式专属 Sections：内容 4 Tab / 排期 / 教师分配 / 学生名单
           ================================================================ -->
      <template v-if="!isNew">
        <!-- 内容 4 Tab：md-editor-v3 逐 Tab 独立保存（设计 §2.4.4） -->
        <section class="mt-6 rounded-xl border border-border bg-surface">
          <div class="flex items-center justify-between border-b border-border px-6 py-4">
            <h2 class="text-base font-semibold text-text">课程内容</h2>
            <p class="text-xs text-text-subtle">四个 Tab 独立保存，互不影响</p>
          </div>

          <!-- 内容区加载中：与编辑器同形的灰块 -->
          <div v-if="contentsLoading" class="p-6">
            <div class="h-12 animate-pulse rounded bg-slate-100" />
            <div class="mt-4 h-64 animate-pulse rounded bg-slate-100" />
          </div>

          <!-- 内容区错误态：横幅 + 重试（不影响页面其它 Section） -->
          <div v-else-if="contentsError" data-testid="contents-error" class="p-6">
            <div
              class="flex items-center justify-between gap-4 rounded-lg border border-danger/30 bg-red-50 px-4 py-3"
            >
              <span class="text-sm text-danger">{{ contentsError }}</span>
              <Button
                variant="outline"
                size="sm"
                data-testid="retry-contents"
                @click="loadContents"
              >
                重试
              </Button>
            </div>
          </div>

          <template v-else>
            <!-- Tab 切换条：激活态 brand 下划线 -->
            <div class="flex gap-1 border-b border-border px-6 pt-3">
              <button
                v-for="tab in tabOrder"
                :key="tab.type"
                type="button"
                :data-testid="`tab-${tab.type}`"
                class="-mb-px border-b-2 px-4 py-2.5 text-sm transition-colors duration-150"
                :class="
                  activeTab === tab.type
                    ? 'border-brand font-medium text-brand-strong'
                    : 'border-transparent text-text-muted hover:text-text'
                "
                @click="activeTab = tab.type"
              >
                {{ tab.label }}
              </button>
            </div>

            <!-- md-editor-v3 编辑器：绑定当前 Tab 正文（onChange/update:modelValue 双通道回写） -->
            <div class="p-6">
              <MdEditor
                :model-value="activeContent"
                :style="{ height: '420px' }"
                @update:model-value="handleContentEdit"
                @on-change="handleContentEdit"
              />
            </div>

            <!-- 保存行：仅保存当前 Tab（PUT 裸 JSON 字符串 body） -->
            <div
              class="flex items-center justify-end gap-3 border-t border-border bg-surface-2 px-6 py-3"
            >
              <span class="text-xs text-text-subtle">当前保存：{{ labelOf(activeTab) }}</span>
              <Button data-testid="save-content" :disabled="contentSaving" @click="saveContent">
                <PhSpinnerGap v-if="contentSaving" class="h-4 w-4 animate-spin" />
                {{ contentSaving ? '保存中' : '保存本页内容' }}
              </Button>
            </div>
          </template>
        </section>

        <!-- 排期 + 教师分配双栏（3:2 不对称网格，设计 §0.2 禁三等宽） -->
        <div class="mt-6 grid grid-cols-3 gap-6">
          <!-- 排期 Section：表格 + 行内增删改 -->
          <section class="col-span-2 overflow-hidden rounded-xl border border-border bg-surface">
            <div class="flex items-center justify-between border-b border-border px-6 py-4">
              <h2 class="text-base font-semibold text-text">排期</h2>
              <Button size="sm" data-testid="add-schedule" @click="openCreateSchedule">
                <PhPlus class="h-4 w-4" />
                新增排期
              </Button>
            </div>

            <!-- 排期空态 -->
            <div
              v-if="schedules.length === 0"
              class="flex flex-col items-center justify-center py-10 text-center"
            >
              <PhWarningCircle class="h-6 w-6 text-text-subtle" />
              <p class="mt-2 text-sm text-text-muted">还没有排期，点击新增排期添加课程安排</p>
            </div>

            <!-- 排期表格：起止/类型/地点/讲师/容量/已报 + 操作 -->
            <table v-else data-testid="schedule-table" class="w-full text-sm">
              <thead class="border-b border-border bg-surface-2 text-left text-xs text-text-muted">
                <tr>
                  <th class="px-4 py-2.5 font-medium">起止日期</th>
                  <th class="w-20 px-4 py-2.5 font-medium">类型</th>
                  <th class="max-w-[140px] px-4 py-2.5 font-medium">地点</th>
                  <th class="max-w-[120px] px-4 py-2.5 font-medium">讲师</th>
                  <th class="w-16 px-4 py-2.5 text-right font-medium">容量</th>
                  <th class="w-16 px-4 py-2.5 text-right font-medium">已报</th>
                  <th class="w-32 px-4 py-2.5 text-right font-medium">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="s in schedules"
                  :key="s.id"
                  :data-testid="`schedule-row-${s.id}`"
                  class="h-11 border-b border-border last:border-b-0 transition-colors duration-150 hover:bg-surface-2"
                >
                  <td class="px-4">
                    <span class="tabular-nums text-text">{{ s.startDate }}</span>
                    <span class="mx-1 text-text-subtle">至</span>
                    <span class="tabular-nums text-text-muted">{{ s.endDate }}</span>
                  </td>
                  <td class="px-4 text-text-muted">{{ s.scheduleType }}</td>
                  <td class="max-w-[140px] truncate px-4 text-text-muted" :title="s.location">
                    {{ s.location || '-' }}
                  </td>
                  <td class="max-w-[120px] truncate px-4 text-text-muted" :title="s.instructorName">
                    {{ s.instructorName || '-' }}
                  </td>
                  <td class="px-4 text-right tabular-nums text-text">{{ s.capacity }}</td>
                  <td class="px-4 text-right tabular-nums text-text-muted">{{ s.enrolled }}</td>
                  <td class="px-4 text-right">
                    <div class="flex items-center justify-end gap-1">
                      <Button
                        variant="ghost"
                        size="sm"
                        :data-testid="`op-schedule-edit-${s.id}`"
                        @click="openEditSchedule(s)"
                      >
                        <PhPencilSimple class="h-3.5 w-3.5" />
                        编辑
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        class="text-danger hover:bg-red-50"
                        :data-testid="`op-schedule-del-${s.id}`"
                        @click="requestDeleteSchedule(s)"
                      >
                        <PhTrash class="h-3.5 w-3.5" />
                        删除
                      </Button>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </section>

          <!-- 教师分配：双栏多选（可选 = 全量 TEACHER 剔除已分配 + 搜索） -->
          <section class="flex flex-col rounded-xl border border-border bg-surface">
            <div class="flex items-center justify-between border-b border-border px-6 py-4">
              <h2 class="text-base font-semibold text-text">教师分配</h2>
            </div>

            <div class="flex flex-1 flex-col gap-4 p-6">
              <!-- 搜索过滤（后端 /users 无 keyword 参数，客户端过滤） -->
              <input
                v-model="teacherSearch"
                type="text"
                data-testid="teacher-search"
                aria-label="搜索教师"
                placeholder="搜索教师（显示名/用户名）"
                class="h-9 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
              />

              <!-- 已分配列表（含移除按钮） -->
              <div>
                <p class="mb-1.5 text-xs font-semibold uppercase tracking-wider text-text-subtle">
                  已分配 {{ assignedTeachers.length }}
                </p>
                <div
                  v-if="assignedTeachers.length === 0"
                  class="rounded-lg bg-surface-2 px-3 py-2 text-xs text-text-subtle"
                >
                  暂无已分配教师
                </div>
                <ul v-else class="space-y-1.5">
                  <li
                    v-for="t in assignedTeachers"
                    :key="t.id"
                    :data-testid="`teacher-assigned-${t.id}`"
                    class="flex items-center justify-between gap-2 rounded-lg bg-surface-2 px-3 py-2"
                  >
                    <span class="truncate text-sm text-text">{{ t.displayName }}</span>
                    <Button
                      variant="ghost"
                      size="sm"
                      class="h-6 px-2 text-xs text-danger hover:bg-red-50"
                      :data-testid="`teacher-remove-${t.id}`"
                      :disabled="teacherRemovingId === t.id"
                      @click="removeTeacher(t)"
                    >
                      <PhSpinnerGap
                        v-if="teacherRemovingId === t.id"
                        class="h-3 w-3 animate-spin"
                      />
                      移除
                    </Button>
                  </li>
                </ul>
              </div>

              <!-- 可选列表：复选框勾选待分配 -->
              <div class="min-h-0 flex-1">
                <p class="mb-1.5 text-xs font-semibold uppercase tracking-wider text-text-subtle">
                  可选教师
                </p>
                <div class="max-h-56 space-y-1.5 overflow-y-auto pr-1">
                  <label
                    v-for="t in availableTeachers"
                    :key="t.id"
                    :data-testid="`teacher-available-${t.id}`"
                    class="flex cursor-pointer items-center gap-2 rounded-lg border border-border px-3 py-2 transition-colors duration-150 hover:bg-surface-2"
                  >
                    <input
                      v-model="teacherSelected"
                      type="checkbox"
                      :value="t.id"
                      :data-testid="`teacher-check-${t.id}`"
                      class="h-4 w-4 accent-brand"
                    />
                    <span class="truncate text-sm text-text">{{ t.displayName }}</span>
                    <span class="truncate text-xs text-text-subtle">{{ t.username }}</span>
                  </label>
                  <div
                    v-if="availableTeachers.length === 0"
                    data-testid="teacher-available-empty"
                    class="rounded-lg bg-surface-2 px-3 py-2 text-xs text-text-subtle"
                  >
                    没有匹配的教师
                  </div>
                </div>
              </div>

              <!-- 分配按钮：POST [ids] 数组 -->
              <Button
                data-testid="teacher-assign"
                :disabled="teacherSelected.length === 0 || teacherAssigning"
                @click="assignTeachers"
              >
                <PhSpinnerGap v-if="teacherAssigning" class="h-4 w-4 animate-spin" />
                分配所选（{{ teacherSelected.length }}）
              </Button>
            </div>
          </section>
        </div>

        <!-- 学生名单 Section：已选列表 + 添加 Dialog + 行移除 -->
        <section class="mt-6 overflow-hidden rounded-xl border border-border bg-surface">
          <div class="flex items-center justify-between border-b border-border px-6 py-4">
            <h2 class="text-base font-semibold text-text">
              学生名单
              <span class="ml-2 text-sm font-normal text-text-muted"
                >共 {{ students.length }} 名</span
              >
            </h2>
            <Button size="sm" data-testid="add-students" @click="openStudentDialog">
              <PhUserPlus class="h-4 w-4" />
              添加学生
            </Button>
          </div>

          <!-- 学生空态 -->
          <div
            v-if="students.length === 0"
            class="flex flex-col items-center justify-center py-10 text-center"
          >
            <PhWarningCircle class="h-6 w-6 text-text-subtle" />
            <p class="mt-2 text-sm text-text-muted">还没有学生报名，点击添加学生开通名额</p>
          </div>

          <!-- 学生表格：username / displayName / enrolledAt + 移除 -->
          <table v-else data-testid="student-table" class="w-full text-sm">
            <thead class="border-b border-border bg-surface-2 text-left text-xs text-text-muted">
              <tr>
                <th class="px-4 py-2.5 font-medium">用户名</th>
                <th class="px-4 py-2.5 font-medium">显示名</th>
                <th class="px-4 py-2.5 font-medium">报名时间</th>
                <th class="w-28 px-4 py-2.5 text-right font-medium">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="s in students"
                :key="s.id"
                :data-testid="`student-row-${s.id}`"
                class="h-11 border-b border-border last:border-b-0 transition-colors duration-150 hover:bg-surface-2"
              >
                <td class="px-4 font-medium text-text">{{ s.username }}</td>
                <td class="px-4 text-text-muted">{{ s.displayName }}</td>
                <td class="px-4 tabular-nums text-text-muted">
                  {{ formatDateTime(s.enrolledAt) }}
                </td>
                <td class="px-4 text-right">
                  <Button
                    variant="ghost"
                    size="sm"
                    class="text-danger hover:bg-red-50"
                    :data-testid="`student-remove-${s.id}`"
                    @click="requestDeleteStudent(s)"
                  >
                    <PhTrash class="h-3.5 w-3.5" />
                    移除
                  </Button>
                </td>
              </tr>
            </tbody>
          </table>
        </section>
      </template>
    </template>

    <!-- ================================================================
         排期新增/编辑 Dialog（480px；提交期 Esc/遮罩/取消全拦截）
         ================================================================ -->
    <div
      v-if="scheduleDialogOpen"
      data-testid="schedule-dialog"
      class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4"
      @keydown.esc="closeScheduleDialog"
      @click.self="closeScheduleDialog"
    >
      <div
        class="w-full max-w-[480px] rounded-xl border border-border bg-surface p-6 shadow-md"
        role="dialog"
        aria-modal="true"
      >
        <h2 class="text-base font-semibold text-text">
          {{ scheduleEditing ? '编辑排期' : '新增排期' }}
        </h2>
        <div class="mt-5 space-y-4">
          <div class="grid grid-cols-2 gap-4">
            <div>
              <label for="schedule-start-date" class="mb-1.5 block text-sm font-medium text-text">
                开始日期 <span class="text-danger">*</span>
              </label>
              <input
                id="schedule-start-date"
                v-model="scheduleForm.startDate"
                type="date"
                data-testid="schedule-start"
                aria-label="开始日期"
                class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm tabular-nums text-text outline-none transition-colors duration-150 focus:border-brand focus:ring-2 focus:ring-brand/20"
              />
            </div>
            <div>
              <label for="schedule-end-date" class="mb-1.5 block text-sm font-medium text-text">
                结束日期 <span class="text-danger">*</span>
              </label>
              <input
                id="schedule-end-date"
                v-model="scheduleForm.endDate"
                type="date"
                data-testid="schedule-end"
                aria-label="结束日期"
                class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm tabular-nums text-text outline-none transition-colors duration-150 focus:border-brand focus:ring-2 focus:ring-brand/20"
              />
            </div>
          </div>
          <div>
            <label for="schedule-type" class="mb-1.5 block text-sm font-medium text-text"
              >类型</label
            >
            <select
              id="schedule-type"
              v-model="scheduleForm.scheduleType"
              data-testid="schedule-type"
              aria-label="排期类型"
              class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 focus:border-brand focus:ring-2 focus:ring-brand/20"
            >
              <option value="ONLINE">ONLINE（线上）</option>
              <option value="OFFLINE">OFFLINE（线下）</option>
              <option value="HYBRID">HYBRID（混合）</option>
            </select>
          </div>
          <div>
            <label for="schedule-location" class="mb-1.5 block text-sm font-medium text-text"
              >地点</label
            >
            <input
              id="schedule-location"
              v-model="scheduleForm.location"
              type="text"
              data-testid="schedule-location"
              aria-label="排期地点"
              placeholder="如 腾讯会议 / 上海教室"
              class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
            />
          </div>
          <div class="grid grid-cols-2 gap-4">
            <div>
              <label for="schedule-instructor" class="mb-1.5 block text-sm font-medium text-text">
                讲师
              </label>
              <input
                id="schedule-instructor"
                v-model="scheduleForm.instructorName"
                type="text"
                data-testid="schedule-instructor"
                aria-label="排期讲师"
                placeholder="主讲老师"
                class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
              />
            </div>
            <div>
              <label for="schedule-capacity" class="mb-1.5 block text-sm font-medium text-text">
                容量
              </label>
              <input
                id="schedule-capacity"
                v-model="scheduleForm.capacity"
                type="number"
                min="0"
                data-testid="schedule-capacity"
                aria-label="排期容量"
                placeholder="人数上限"
                class="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm tabular-nums text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
              />
            </div>
          </div>
          <!-- 校验错误：就地红字（不发请求） -->
          <p v-if="scheduleError" data-testid="schedule-error" class="text-xs text-danger">
            {{ scheduleError }}
          </p>
        </div>
        <div class="mt-5 flex justify-end gap-2">
          <Button
            variant="outline"
            data-testid="cancel-schedule"
            :disabled="scheduleSubmitting"
            @click="closeScheduleDialog"
          >
            取消
          </Button>
          <Button
            data-testid="submit-schedule"
            :disabled="scheduleSubmitting"
            @click="submitSchedule"
          >
            <PhSpinnerGap v-if="scheduleSubmitting" class="h-4 w-4 animate-spin" />
            {{ scheduleSubmitting ? '保存中' : '保存' }}
          </Button>
        </div>
      </div>
    </div>

    <!-- 排期删除二次确认（danger 实底；提交期拦截关闭） -->
    <div
      v-if="scheduleDeleting"
      data-testid="schedule-del-dialog"
      class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4"
      @keydown.esc="cancelDeleteSchedule"
      @click.self="cancelDeleteSchedule"
    >
      <div
        class="w-full max-w-[440px] rounded-xl border border-border bg-surface p-6 shadow-md"
        role="alertdialog"
        aria-modal="true"
      >
        <div class="flex items-start gap-3">
          <div class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-red-50">
            <PhWarningCircle class="h-5 w-5 text-danger" />
          </div>
          <div>
            <h2 class="text-base font-semibold text-text">删除排期</h2>
            <p class="mt-2 text-sm leading-relaxed text-text-muted">
              将删除
              <span class="tabular-nums">{{ scheduleDeleting.startDate }}</span>
              至
              <span class="tabular-nums">{{ scheduleDeleting.endDate }}</span>
              的排期，此操作不可恢复。确认删除？
            </p>
          </div>
        </div>
        <div class="mt-5 flex justify-end gap-2">
          <Button
            variant="outline"
            data-testid="cancel-schedule-del"
            :disabled="scheduleDeletingLoading"
            @click="cancelDeleteSchedule"
          >
            取消
          </Button>
          <Button
            variant="danger"
            data-testid="confirm-schedule-del"
            :disabled="scheduleDeletingLoading"
            @click="confirmDeleteSchedule"
          >
            <PhSpinnerGap v-if="scheduleDeletingLoading" class="h-4 w-4 animate-spin" />
            {{ scheduleDeletingLoading ? '删除中' : '确认删除' }}
          </Button>
        </div>
      </div>
    </div>

    <!-- ================================================================
         添加学生 Dialog（搜索多选；提交期 Esc/遮罩/取消全拦截）
         ================================================================ -->
    <div
      v-if="studentDialogOpen"
      data-testid="student-dialog"
      class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4"
      @keydown.esc="closeStudentDialog"
      @click.self="closeStudentDialog"
    >
      <div
        class="flex max-h-[560px] w-full max-w-[480px] flex-col rounded-xl border border-border bg-surface p-6 shadow-md"
        role="dialog"
        aria-modal="true"
      >
        <h2 class="text-base font-semibold text-text">添加学生</h2>
        <input
          v-model="studentSearch"
          type="text"
          data-testid="student-search"
          aria-label="搜索学生"
          placeholder="搜索学生（显示名/用户名）"
          class="mt-4 h-9 w-full rounded-lg border border-border bg-surface px-3 text-sm text-text outline-none transition-colors duration-150 placeholder:text-text-subtle focus:border-brand focus:ring-2 focus:ring-brand/20"
        />
        <!-- 候选列表：整行点击切换多选（已报名学生自动剔除） -->
        <div class="mt-3 min-h-0 flex-1 space-y-1.5 overflow-y-auto border-y border-border py-3">
          <div v-if="studentCandidatesLoading" class="space-y-1.5">
            <div
              v-for="i in 4"
              :key="`cand-${i}`"
              class="h-10 animate-pulse rounded bg-slate-100"
            />
          </div>
          <button
            v-for="u in studentOptions"
            :key="u.id"
            type="button"
            :data-testid="`student-option-${u.id}`"
            class="flex w-full cursor-pointer items-center justify-between gap-2 rounded-lg border border-border px-3 py-2 text-left transition-colors duration-150 hover:bg-surface-2"
            :class="studentSelected.includes(u.id) ? 'border-brand bg-brand-soft/50' : ''"
            @click="toggleStudent(u)"
          >
            <span class="truncate text-sm text-text">{{ u.displayName }}</span>
            <span class="truncate text-xs text-text-subtle">{{ u.username }}</span>
            <!-- 勾选态指示（选中 brand 实心勾，未选空心框） -->
            <span
              class="flex h-4 w-4 shrink-0 items-center justify-center rounded-sm border"
              :class="
                studentSelected.includes(u.id)
                  ? 'border-brand bg-brand text-white'
                  : 'border-border bg-surface'
              "
            >
              <PhCheck v-if="studentSelected.includes(u.id)" class="h-3 w-3" weight="bold" />
            </span>
          </button>
          <div
            v-if="!studentCandidatesLoading && studentOptions.length === 0"
            class="py-4 text-center text-xs text-text-subtle"
          >
            没有可添加的学生
          </div>
        </div>
        <div class="mt-4 flex justify-end gap-2">
          <Button
            variant="outline"
            data-testid="cancel-students"
            :disabled="studentSubmitting"
            @click="closeStudentDialog"
          >
            取消
          </Button>
          <Button
            data-testid="submit-students"
            :disabled="studentSelected.length === 0 || studentSubmitting"
            @click="submitStudents"
          >
            <PhSpinnerGap v-if="studentSubmitting" class="h-4 w-4 animate-spin" />
            {{ studentSubmitting ? '添加中' : `添加所选（${studentSelected.length}）` }}
          </Button>
        </div>
      </div>
    </div>

    <!-- 移除学生二次确认（danger 实底；提交期拦截关闭） -->
    <div
      v-if="studentDeleting"
      data-testid="student-del-dialog"
      class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4"
      @keydown.esc="cancelDeleteStudent"
      @click.self="cancelDeleteStudent"
    >
      <div
        class="w-full max-w-[440px] rounded-xl border border-border bg-surface p-6 shadow-md"
        role="alertdialog"
        aria-modal="true"
      >
        <div class="flex items-start gap-3">
          <div class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-red-50">
            <PhWarningCircle class="h-5 w-5 text-danger" />
          </div>
          <div>
            <h2 class="text-base font-semibold text-text">移除学生</h2>
            <p class="mt-2 text-sm leading-relaxed text-text-muted">
              将移除「{{ studentDeleting.displayName }}」的课程名额与报名关系，确认移除？
            </p>
          </div>
        </div>
        <div class="mt-5 flex justify-end gap-2">
          <Button
            variant="outline"
            data-testid="cancel-student-del"
            :disabled="studentDeletingLoading"
            @click="cancelDeleteStudent"
          >
            取消
          </Button>
          <Button
            variant="danger"
            data-testid="confirm-student-del"
            :disabled="studentDeletingLoading"
            @click="confirmDeleteStudent"
          >
            <PhSpinnerGap v-if="studentDeletingLoading" class="h-4 w-4 animate-spin" />
            {{ studentDeletingLoading ? '移除中' : '确认移除' }}
          </Button>
        </div>
      </div>
    </div>
  </main>
</template>
