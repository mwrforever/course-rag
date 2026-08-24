import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, userApi } from '@/lib/api'
import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'
import UsersView from '@/views/UsersView.vue'

import type { PageResponse, UserDTO, UserRole } from '@/lib/types'

/**
 * 用户管理页测试（Task 21 核心交付）
 *
 * 覆盖契约（设计 §2.4.5 + task-21 brief）：
 * 1. 角色 Tab：全部/教师/学生（点击触发 list 携带 role 参数，计数来自列表 total）
 * 2. 添加入口角色差异：TEACHER 无「添加教师」且 Dialog 无角色选择器（固定 STUDENT）；
 *    SUPER_ADMIN 有「添加教师」且角色选择器含 TEACHER
 * 3. 权限矩阵：当前登录超管自身行禁用按钮隐藏（useAuthStore.userId 比对）
 * 4. 重置密码 Dialog：zod ≥6 前置校验 + 两次输入一致 → resetPassword({newPassword})
 * 5. 禁用/启用二次确认（danger）→ updateStatus({status})
 * 6. 编辑 displayName → update({displayName})；删除二次确认 → remove
 * 7. 四态：loading 骨架 / empty 含添加入口 / error 横幅重试 / 正常
 *
 * 契约要点：id/total 为 Long 字符串铁律；数字域 tabular-nums。
 */
function pageOf<T>(records: T[], total: string, page = 1, size = 10): PageResponse<T> {
  return { records, total, page, size }
}

/** 用户工厂（默认 STUDENT + ACTIVE，便于覆盖各表格列） */
function user(id: string, over: Partial<UserDTO> = {}): UserDTO {
  return {
    id,
    username: `user-${id}`,
    displayName: `用户${id}`,
    role: 'STUDENT',
    status: 'ACTIVE',
    createdAt: '2026-08-20T10:00:00',
    ...over,
  }
}

/** 挂载用户管理页：登录态（可指定角色与当前用户 id，供权限矩阵断言） */
async function mountUsers(role: UserRole, currentUserId = '1001') {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().setAuth({
    accessToken: 'at-1',
    refreshToken: 'rt-1',
    userId: currentUserId,
    role,
    displayName: '当前登录',
  })
  const router = createAppRouter()
  await router.push('/users')
  await router.isReady()
  const wrapper = mount(UsersView, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return { wrapper, router, pinia }
}

describe('UsersView：列表渲染与角色 Tab', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('渲染用户名/显示名/角色 Badge/状态/创建时间/操作，ACTIVE emerald / DISABLED red', async () => {
    vi.spyOn(userApi, 'list').mockResolvedValue(
      pageOf([user('u-1', { role: 'TEACHER' }), user('u-2', { status: 'DISABLED' })], '2'),
    )
    const { wrapper } = await mountUsers('TEACHER')

    // 行内容齐全（用户名/显示名/角色/创建时间）
    expect(wrapper.find('[data-testid="row-u-1"]').text()).toContain('user-u-1')
    expect(wrapper.find('[data-testid="row-u-1"]').text()).toContain('用户u-1')
    expect(wrapper.find('[data-testid="row-u-1"]').text()).toContain('TEACHER')
    expect(wrapper.find('[data-testid="row-u-1"]').text()).toContain('08-20 10:00')

    // 状态 Badge：ACTIVE emerald / DISABLED red（设计 §2.5 用户双色互斥）
    const active = wrapper.find('[data-testid="user-status-u-1"]')
    expect(active.classes()).toContain('bg-emerald-50')
    const disabled = wrapper.find('[data-testid="user-status-u-2"]')
    expect(disabled.classes()).toContain('bg-red-50')

    // 时间列数字域 tabular-nums
    expect(wrapper.find('[data-testid="row-u-1"]').findAll('.tabular-nums').length).toBeGreaterThan(
      0,
    )

    // 操作列：编辑/重置密码/禁用/删除
    expect(wrapper.find('[data-testid="op-edit-u-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="op-reset-u-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="op-disable-u-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="op-delete-u-1"]').exists()).toBe(true)
    // DISABLED 行显示启用而非禁用
    expect(wrapper.find('[data-testid="op-enable-u-2"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="op-disable-u-2"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('角色 Tab：默认全部不带 role，切换教师/学生携带 role 参数且计数来自 total', async () => {
    const listSpy = vi.spyOn(userApi, 'list').mockImplementation(async (params) => {
      const role = params?.role ?? 'ALL'
      return pageOf([user(`u-${role}`)], `9`, 1)
    })
    const { wrapper } = await mountUsers('TEACHER')

    // 初始：tab-all 激活，list 不带 role
    expect(listSpy.mock.calls.at(-1)?.[0]).not.toHaveProperty('role')
    expect(wrapper.find('[data-testid="tab-count-all"]').text()).toContain('9')

    // 切教师 Tab：role=TEACHER，计数 chip 取自该次列表 total
    await wrapper.find('[data-testid="tab-teacher"]').trigger('click')
    await flushPromises()
    expect(listSpy.mock.calls.at(-1)?.[0]).toMatchObject({ role: 'TEACHER' })
    expect(wrapper.find('[data-testid="tab-count-teacher"]').text()).toContain('9')
    expect(wrapper.find('[data-testid="row-u-TEACHER"]').exists()).toBe(true)

    // 切学生 Tab：role=STUDENT
    await wrapper.find('[data-testid="tab-student"]').trigger('click')
    await flushPromises()
    expect(listSpy.mock.calls.at(-1)?.[0]).toMatchObject({ role: 'STUDENT' })
    expect(wrapper.find('[data-testid="row-u-STUDENT"]').exists()).toBe(true)
    wrapper.unmount()
  })
})

describe('UsersView：添加用户角色差异（权限矩阵）', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('TEACHER：无「添加教师」入口；添加 Dialog 无角色选择器，创建固定 role=STUDENT', async () => {
    vi.spyOn(userApi, 'list').mockResolvedValue(pageOf([user('u-1')], '1'))
    const createSpy = vi.spyOn(userApi, 'create').mockResolvedValue(user('u-new'))
    const { wrapper } = await mountUsers('TEACHER')

    // 教师端仅一个入口
    expect(wrapper.find('[data-testid="add-teacher"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="add-student"]').exists()).toBe(true)

    await wrapper.find('[data-testid="add-student"]').trigger('click')
    const dialog = wrapper.find('[data-testid="add-user-dialog"]')
    expect(dialog.exists()).toBe(true)
    // 教师端无角色选择器（固定 STUDENT，隐藏入口）
    expect(wrapper.find('[data-testid="add-role"]').exists()).toBe(false)
    expect(dialog.text()).toContain('添加学生')

    await wrapper.find('[data-testid="add-username"]').setValue('new-stu')
    await wrapper.find('[data-testid="add-password"]').setValue('secret6')
    await wrapper.find('[data-testid="add-displayname"]').setValue('新学生')
    await wrapper.find('[data-testid="add-form"]').trigger('submit')
    await flushPromises()

    expect(createSpy).toHaveBeenCalledWith({
      username: 'new-stu',
      password: 'secret6',
      displayName: '新学生',
      role: 'STUDENT',
    })
    expect(document.body.textContent).toContain('学生账号已创建')
    expect(wrapper.find('[data-testid="add-user-dialog"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('SUPER_ADMIN：有「添加教师」入口；角色选择器含 TEACHER，可按所选角色创建', async () => {
    vi.spyOn(userApi, 'list').mockResolvedValue(pageOf([user('u-1')], '1'))
    const createSpy = vi
      .spyOn(userApi, 'create')
      .mockResolvedValue(user('u-new', { role: 'TEACHER' }))
    const { wrapper } = await mountUsers('SUPER_ADMIN')

    expect(wrapper.find('[data-testid="add-teacher"]').exists()).toBe(true)
    await wrapper.find('[data-testid="add-teacher"]').trigger('click')
    const dialog = wrapper.find('[data-testid="add-user-dialog"]')
    expect(dialog.text()).toContain('添加教师')

    // 角色选择器在场且包含 TEACHER 选项（设计 §2.4.5：仅超管含角色选择器）
    const roleSelect = wrapper.find('[data-testid="add-role"]')
    expect(roleSelect.exists()).toBe(true)
    expect(roleSelect.text()).toContain('TEACHER')
    await roleSelect.setValue('TEACHER')

    await wrapper.find('[data-testid="add-username"]').setValue('new-tea')
    await wrapper.find('[data-testid="add-password"]').setValue('secret6')
    await wrapper.find('[data-testid="add-displayname"]').setValue('新教师')
    await wrapper.find('[data-testid="add-form"]').trigger('submit')
    await flushPromises()

    expect(createSpy).toHaveBeenCalledWith({
      username: 'new-tea',
      password: 'secret6',
      displayName: '新教师',
      role: 'TEACHER',
    })
    expect(document.body.textContent).toContain('教师账号已创建')
    wrapper.unmount()
  })

  it('表单 zod 前置校验：空提交不发请求，就地报错；密码不足 6 位同样拦截', async () => {
    vi.spyOn(userApi, 'list').mockResolvedValue(pageOf([user('u-1')], '1'))
    const createSpy = vi.spyOn(userApi, 'create').mockResolvedValue(user('u-new'))
    const { wrapper } = await mountUsers('TEACHER')

    await wrapper.find('[data-testid="add-student"]').trigger('click')
    await wrapper.find('[data-testid="add-form"]').trigger('submit')
    await flushPromises()

    expect(createSpy).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请输入用户名')
    expect(wrapper.text()).toContain('请输入密码')

    // 密码 5 位 → ≥6 拦截
    await wrapper.find('[data-testid="add-username"]').setValue('stu-a')
    await wrapper.find('[data-testid="add-password"]').setValue('12345')
    await wrapper.find('[data-testid="add-displayname"]').setValue('学生A')
    await wrapper.find('[data-testid="add-form"]').trigger('submit')
    await flushPromises()
    expect(createSpy).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('密码至少 6 位')
    wrapper.unmount()
  })
})

describe('UsersView：权限矩阵（自身禁用隐藏）', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('当前登录超管自身行禁用按钮隐藏；他人行不受影响', async () => {
    vi.spyOn(userApi, 'list').mockResolvedValue(
      pageOf([user('u-9', { role: 'SUPER_ADMIN' }), user('u-2', { role: 'SUPER_ADMIN' })], '2'),
    )
    const { wrapper } = await mountUsers('SUPER_ADMIN', 'u-9')

    // 自身 u-9：禁用按钮隐藏（防止超管把自己禁用锁死）
    expect(wrapper.find('[data-testid="op-disable-u-9"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="op-enable-u-9"]').exists()).toBe(false)
    // 其它超管行：禁用按钮正常在场
    expect(wrapper.find('[data-testid="op-disable-u-2"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('当前登录教师行同样隐藏禁用按钮（任何角色自身不可禁用）', async () => {
    vi.spyOn(userApi, 'list').mockResolvedValue(pageOf([user('u-9', { role: 'TEACHER' })], '1'))
    const { wrapper } = await mountUsers('TEACHER', 'u-9')

    expect(wrapper.find('[data-testid="op-disable-u-9"]').exists()).toBe(false)
    wrapper.unmount()
  })
})

describe('UsersView：编辑 / 重置密码 / 禁用启用 / 删除', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('编辑 displayName：update({displayName}) → toast → 刷新', async () => {
    const listSpy = vi
      .spyOn(userApi, 'list')
      .mockResolvedValueOnce(pageOf([user('u-1')], '1'))
      .mockResolvedValueOnce(pageOf([user('u-1', { displayName: '新名字' })], '1'))
    const updateSpy = vi.spyOn(userApi, 'update').mockResolvedValue(user('u-1'))
    const { wrapper } = await mountUsers('TEACHER')

    await wrapper.find('[data-testid="op-edit-u-1"]').trigger('click')
    await wrapper.find('[data-testid="edit-displayname"]').setValue('新名字')
    await wrapper.find('[data-testid="submit-edit"]').trigger('click')
    await flushPromises()

    expect(updateSpy).toHaveBeenCalledWith('u-1', { displayName: '新名字' })
    expect(document.body.textContent).toContain('显示名已更新')
    expect(listSpy.mock.calls.length).toBeGreaterThan(1)
    wrapper.unmount()
  })

  it('重置密码：新密码 zod ≥6 拦截 + 两次输入不一致拦截 + 成功 resetPassword({newPassword})', async () => {
    vi.spyOn(userApi, 'list').mockResolvedValue(pageOf([user('u-1')], '1'))
    const resetSpy = vi.spyOn(userApi, 'resetPassword').mockResolvedValue()
    const { wrapper } = await mountUsers('TEACHER')

    await wrapper.find('[data-testid="op-reset-u-1"]').trigger('click')
    const dialog = wrapper.find('[data-testid="reset-dialog"]')
    expect(dialog.exists()).toBe(true)
    expect(dialog.text()).toContain('重置密码')

    // 新密码不足 6 位：就地处错，不发请求
    await wrapper.find('[data-testid="reset-password"]').setValue('12345')
    await wrapper.find('[data-testid="reset-confirm"]').setValue('12345')
    await wrapper.find('[data-testid="submit-reset"]').trigger('click')
    await flushPromises()
    expect(resetSpy).not.toHaveBeenCalled()
    expect(dialog.text()).toContain('新密码至少 6 位')

    // 两次输入不一致：拦截
    await wrapper.find('[data-testid="reset-password"]').setValue('abcdef')
    await wrapper.find('[data-testid="reset-confirm"]').setValue('abcdeg')
    await wrapper.find('[data-testid="submit-reset"]').trigger('click')
    await flushPromises()
    expect(resetSpy).not.toHaveBeenCalled()
    expect(dialog.text()).toContain('两次输入的密码不一致')

    // 合法提交
    await wrapper.find('[data-testid="reset-confirm"]').setValue('abcdef')
    await wrapper.find('[data-testid="submit-reset"]').trigger('click')
    await flushPromises()
    expect(resetSpy).toHaveBeenCalledWith('u-1', { newPassword: 'abcdef' })
    expect(document.body.textContent).toContain('密码已重置')
    expect(wrapper.find('[data-testid="reset-dialog"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('禁用：二次确认（danger 实底）→ updateStatus({status:DISABLED})；启用同路径 ACTIVE', async () => {
    const listSpy = vi
      .spyOn(userApi, 'list')
      .mockResolvedValueOnce(pageOf([user('u-1')], '1'))
      .mockResolvedValueOnce(pageOf([user('u-1', { status: 'DISABLED' })], '1'))
    const statusSpy = vi.spyOn(userApi, 'updateStatus').mockResolvedValue()
    const { wrapper } = await mountUsers('TEACHER')

    // 禁用（danger 按钮 + 二次确认）
    const disableBtn = wrapper.find('[data-testid="op-disable-u-1"]')
    expect(disableBtn.classes()).toContain('bg-danger')
    await disableBtn.trigger('click')
    expect(wrapper.find('[data-testid="status-dialog"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="status-dialog"]').text()).toContain('禁用用户')
    await wrapper.find('[data-testid="submit-status"]').trigger('click')
    await flushPromises()
    expect(statusSpy).toHaveBeenCalledWith('u-1', { status: 'DISABLED' })
    expect(document.body.textContent).toContain('已禁用')

    // 刷新后行变 DISABLED → 显示启用
    expect(wrapper.find('[data-testid="op-enable-u-1"]').exists()).toBe(true)
    await wrapper.find('[data-testid="op-enable-u-1"]').trigger('click')
    await wrapper.find('[data-testid="submit-status"]').trigger('click')
    await flushPromises()
    expect(statusSpy).toHaveBeenLastCalledWith('u-1', { status: 'ACTIVE' })
    expect(document.body.textContent).toContain('已启用')
    expect(listSpy.mock.calls.length).toBeGreaterThan(1)
    wrapper.unmount()
  })

  it('删除：二次确认 → remove(id) → toast → 刷新', async () => {
    const listSpy = vi
      .spyOn(userApi, 'list')
      .mockResolvedValueOnce(pageOf([user('u-1'), user('u-2')], '2'))
      .mockResolvedValueOnce(pageOf([user('u-2')], '1'))
    const removeSpy = vi.spyOn(userApi, 'remove').mockResolvedValue()
    const { wrapper } = await mountUsers('TEACHER')

    await wrapper.find('[data-testid="op-delete-u-1"]').trigger('click')
    expect(wrapper.find('[data-testid="user-del-dialog"]').exists()).toBe(true)
    await wrapper.find('[data-testid="confirm-user-del"]').trigger('click')
    await flushPromises()

    expect(removeSpy).toHaveBeenCalledWith('u-1')
    expect(document.body.textContent).toContain('用户已删除')
    expect(wrapper.find('[data-testid="row-u-1"]').exists()).toBe(false)
    expect(listSpy.mock.calls.length).toBeGreaterThan(1)
    wrapper.unmount()
  })
})

describe('UsersView：四态', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('loading：表格骨架屏在场，不出数据', async () => {
    vi.spyOn(userApi, 'list').mockReturnValue(new Promise(() => {}))
    const { wrapper } = await mountUsers('TEACHER')

    expect(wrapper.find('[data-testid="user-skeleton"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="user-table"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('error：503 统一降级文案 + 重试恢复', async () => {
    vi.spyOn(userApi, 'list')
      .mockRejectedValueOnce(new ApiError(503, '服务暂时不可用', 503))
      .mockResolvedValue(pageOf([user('u-1')], '1'))
    const { wrapper } = await mountUsers('TEACHER')

    expect(wrapper.find('[role="alert"]').text()).toContain('服务暂时不可用，请稍后重试')
    await wrapper.find('[data-testid="retry-users"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="row-u-1"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('empty：空态文案 + 添加入口（禁裸「暂无数据」）', async () => {
    vi.spyOn(userApi, 'list').mockResolvedValue(pageOf<UserDTO>([], '0'))
    const { wrapper } = await mountUsers('TEACHER')

    expect(wrapper.text()).toContain('还没有用户')
    expect(wrapper.find('[data-testid="user-table"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="add-student-empty"]').exists()).toBe(true)
    wrapper.unmount()
  })
})

describe('UsersView：取消路径与失败路径', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('五个 Dialog 取消路径：均不调接口且正常关闭', async () => {
    vi.spyOn(userApi, 'list').mockResolvedValue(pageOf([user('u-1')], '1'))
    const createSpy = vi.spyOn(userApi, 'create')
    const updateSpy = vi.spyOn(userApi, 'update')
    const resetSpy = vi.spyOn(userApi, 'resetPassword')
    const statusSpy = vi.spyOn(userApi, 'updateStatus')
    const removeSpy = vi.spyOn(userApi, 'remove')
    const { wrapper } = await mountUsers('SUPER_ADMIN')

    // 添加 Dialog 取消（取消按钮）
    await wrapper.find('[data-testid="add-teacher"]').trigger('click')
    await wrapper.find('[data-testid="cancel-add-user"]').trigger('click')
    expect(wrapper.find('[data-testid="add-user-dialog"]').exists()).toBe(false)

    // 编辑 Dialog Esc 关闭
    await wrapper.find('[data-testid="op-edit-u-1"]').trigger('click')
    await wrapper.find('[data-testid="edit-dialog"]').trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('[data-testid="edit-dialog"]').exists()).toBe(false)

    // 重置密码 Dialog 取消
    await wrapper.find('[data-testid="op-reset-u-1"]').trigger('click')
    await wrapper.find('[data-testid="cancel-reset"]').trigger('click')
    expect(wrapper.find('[data-testid="reset-dialog"]').exists()).toBe(false)

    // 状态切换 Dialog 取消 + 遮罩点击关闭
    await wrapper.find('[data-testid="op-disable-u-1"]').trigger('click')
    await wrapper.find('[data-testid="cancel-status"]').trigger('click')
    expect(wrapper.find('[data-testid="status-dialog"]').exists()).toBe(false)

    // 删除 Dialog 取消
    await wrapper.find('[data-testid="op-delete-u-1"]').trigger('click')
    await wrapper.find('[data-testid="cancel-user-del"]').trigger('click')
    expect(wrapper.find('[data-testid="user-del-dialog"]').exists()).toBe(false)

    expect(createSpy).not.toHaveBeenCalled()
    expect(updateSpy).not.toHaveBeenCalled()
    expect(resetSpy).not.toHaveBeenCalled()
    expect(statusSpy).not.toHaveBeenCalled()
    expect(removeSpy).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('操作失败路径：danger toast 且 Dialog 保留可重试', async () => {
    vi.spyOn(userApi, 'list').mockResolvedValue(pageOf([user('u-1')], '1'))
    // 后端错误消息互不相同，防跨用例残留 toast 造成误判
    vi.spyOn(userApi, 'create').mockRejectedValue(new ApiError(500, '创建用户后端拒绝', 500))
    vi.spyOn(userApi, 'update').mockRejectedValue(new ApiError(500, '更新显示名后端拒绝', 500))
    vi.spyOn(userApi, 'resetPassword').mockRejectedValue(new ApiError(500, '重置密码后端拒绝', 500))
    vi.spyOn(userApi, 'updateStatus').mockRejectedValue(new ApiError(500, '状态变更后端拒绝', 500))
    vi.spyOn(userApi, 'remove').mockRejectedValue(new ApiError(500, '删除用户后端拒绝', 500))
    const { wrapper } = await mountUsers('TEACHER')

    // 创建失败：Dialog 保留
    await wrapper.find('[data-testid="add-student"]').trigger('click')
    await wrapper.find('[data-testid="add-username"]').setValue('new-stu')
    await wrapper.find('[data-testid="add-password"]').setValue('secret6')
    await wrapper.find('[data-testid="add-displayname"]').setValue('新学生')
    await wrapper.find('[data-testid="add-form"]').trigger('submit')
    await flushPromises()
    expect(document.body.textContent).toContain('创建用户后端拒绝')
    expect(wrapper.find('[data-testid="add-user-dialog"]').exists()).toBe(true)

    // 编辑失败：Dialog 保留
    await wrapper.find('[data-testid="op-edit-u-1"]').trigger('click')
    await wrapper.find('[data-testid="submit-edit"]').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('更新显示名后端拒绝')
    expect(wrapper.find('[data-testid="edit-dialog"]').exists()).toBe(true)

    // 重置失败：Dialog 保留
    await wrapper.find('[data-testid="op-reset-u-1"]').trigger('click')
    await wrapper.find('[data-testid="reset-password"]').setValue('abcdef')
    await wrapper.find('[data-testid="reset-confirm"]').setValue('abcdef')
    await wrapper.find('[data-testid="submit-reset"]').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('重置密码后端拒绝')
    expect(wrapper.find('[data-testid="reset-dialog"]').exists()).toBe(true)

    // 状态切换失败：Dialog 保留
    await wrapper.find('[data-testid="op-disable-u-1"]').trigger('click')
    await wrapper.find('[data-testid="submit-status"]').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('状态变更后端拒绝')
    expect(wrapper.find('[data-testid="status-dialog"]').exists()).toBe(true)

    // 删除失败：Dialog 保留
    await wrapper.find('[data-testid="op-delete-u-1"]').trigger('click')
    await wrapper.find('[data-testid="confirm-user-del"]').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('删除用户后端拒绝')
    expect(wrapper.find('[data-testid="user-del-dialog"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('非 ApiError 异常：页面兜底文案（不分级透出）', async () => {
    vi.spyOn(userApi, 'list').mockRejectedValueOnce(new Error('boom'))
    const { wrapper } = await mountUsers('TEACHER')

    expect(wrapper.find('[role="alert"]').text()).toContain('用户列表加载失败，请稍后重试')
    wrapper.unmount()
  })
})

describe('UsersView：分页与删除末页回退', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('分页：共 N 条 + 翻页携带 page 参数 + 上一页越界禁用', async () => {
    const listSpy = vi.spyOn(userApi, 'list').mockImplementation(async (params) => {
      const p = params?.page ?? 1
      return pageOf([user(`u-${p}`)], '25', p)
    })
    const { wrapper } = await mountUsers('SUPER_ADMIN')

    expect(wrapper.text()).toContain('共 25 条')
    expect(wrapper.text()).toContain('第 1 / 3 页')
    expect((wrapper.find('[data-testid="prev-page"]').element as HTMLButtonElement).disabled).toBe(
      true,
    )

    await wrapper.find('[data-testid="next-page"]').trigger('click')
    await flushPromises()
    expect(listSpy.mock.calls.at(-1)?.[0]).toMatchObject({ page: 2, size: 10 })
    expect(wrapper.find('[data-testid="row-u-2"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('删除末页最后一行后自动回退上一页（防空页停留）', async () => {
    // 第 1 页 10 条 + 第 2 页 1 条；删除第 2 页最后一行 → 该页列表为空 → 回退第 1 页重拉
    const deleted = new Set<string>()
    const listSpy = vi.spyOn(userApi, 'list').mockImplementation(async (params) => {
      const p = params?.page ?? 1
      if (p === 1) {
        // 第 1 页 10 条；删除后总量同步减少（真实后端语义）
        return pageOf(
          Array.from({ length: 10 }, (_, i) => user(`u-${i + 1}`)),
          String(11 - deleted.size),
          1,
        )
      }
      // 第 2 页：模拟删除即时生效（u-90 删除后该页记录数为 0）
      const remaining = [user('u-90')].filter((u) => !deleted.has(u.id))
      return pageOf(remaining, String(11 - deleted.size), 2)
    })
    const removeSpy = vi.spyOn(userApi, 'remove').mockImplementation(async (id) => {
      deleted.add(id)
    })
    const { wrapper } = await mountUsers('TEACHER')

    await wrapper.find('[data-testid="next-page"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="row-u-90"]').exists()).toBe(true)

    await wrapper.find('[data-testid="op-delete-u-90"]').trigger('click')
    await wrapper.find('[data-testid="confirm-user-del"]').trigger('click')
    await flushPromises()

    expect(removeSpy).toHaveBeenCalledWith('u-90')
    // 回退第 1 页：恢复展示第一页行且页码回到第 1 页（剩余总数 10 → 共 1 页）
    expect(wrapper.text()).toContain('第 1 / 1 页')
    expect(wrapper.find('[data-testid="row-u-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="row-u-90"]').exists()).toBe(false)
    expect(listSpy.mock.calls.length).toBeGreaterThan(2)
    wrapper.unmount()
  })
})
