/**
 * 课程编辑域共享查询契约（PERF-09 / PERF-11 查询键统一）
 *
 * 职责：集中定义课程详情与教师/学生池的统一查询键与查询函数——
 * 详情壳（CourseDetailLayout）/ 概览（CourseOverviewView）/ 教师分配（CourseTeachersView）
 * 共享 ['course', id] 单键缓存，Tab 首访切换同键去重 0 重复请求；
 * 教师池/学生池统一 ['user-pool', role] 键，页面池查询与 remote-select fetcher
 * （queryClient.ensureQueryData）共享缓存，30s staleTime 窗口内关键字搜索纯本地过滤。
 *
 * 注意：查询函数为纯数据拉取（无组件态），404 以 null 表达（与错误态区分），
 * 消费方须容忍 null（详情壳据此渲染「课程不存在」，子视图经详情壳门控不直接触达 null）。
 */
import { ApiError, courseApi, userApi } from '@/lib/api'

import type { CourseDTO, UserDTO } from '@/lib/types'

/** 池角色字面量（教师池/学生池共用同一查询键族） */
export type UserPoolRole = 'TEACHER' | 'STUDENT'

/**
 * 课程详情统一查询键（PERF-11：详情壳/概览/教师分配三视图共享）
 *
 * @param id 课程 id（Long 字符串铁律）
 * @returns 查询键数组（精确到单课程，失效按课程收敛）
 */
export function courseDetailKey(id: string) {
  return ['course', id] as const
}

/**
 * 课程详情查询函数（统一 404 语义：返回 null 而非抛错）
 *
 * @param id 课程 id
 * @returns 课程详情；404（不存在/已下架）返回 null；其余错误原样抛出（走错误态横幅）
 */
export async function fetchCourseDetail(id: string): Promise<CourseDTO | null> {
  try {
    return await courseApi.get(id)
  } catch (err) {
    if (err instanceof ApiError && err.code === 404) return null
    throw err
  }
}

/**
 * 用户池统一查询键（PERF-09：页面池查询与 remote-select fetcher 共享）
 *
 * @param role 池角色（TEACHER 教师 / STUDENT 学生）
 * @returns 查询键数组（角色维度跨课程共享——池数据不随课程变化）
 */
export function userPoolKey(role: UserPoolRole) {
  return ['user-pool', role] as const
}

/**
 * 用户池查询函数：整池拉取 + 角色客户端兜底过滤
 *
 * 后端 /admin/users 无 keyword 参数：remote-select 关键字过滤由各 fetcher 在池数据上本地做。
 *
 * @param role 池角色
 * @returns 该角色的用户池（size=100 上限，与原三视图 fetcher 口径一致）
 */
export async function fetchUserPool(role: UserPoolRole): Promise<UserDTO[]> {
  const res = await userApi.list({ role, size: 100 })
  return (res.records ?? []).filter((u) => u.role === role)
}
