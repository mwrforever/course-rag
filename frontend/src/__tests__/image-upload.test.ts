import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ImageUpload } from '@/components/ui/image-upload'

/**
 * 封面上传组件测试（契约 F 行为清单）
 *
 * 覆盖：空态引导 / 校验失败内联不发请求 / 上传成功回传 url /
 * 上传失败保留原图 + 重试 / 删除确认回传 null / 上传中禁止重复选择 / 拖拽上传。
 */

/** 构造测试图片文件（扩展名与大小可控） */
function imageFile(name: string, size = 1024): File {
  return new File([new ArrayBuffer(size)], name, { type: 'image/png' })
}

/** api mock：apiClient.post 承载上传（统一解包后 data 即业务数据） */
const apiMock = vi.hoisted(() => ({
  apiClient: { post: vi.fn() },
  ApiError: class ApiError extends Error {
    code: number
    constructor(code: number, message: string) {
      super(message)
      this.code = code
    }
  },
}))
vi.mock('@/lib/api', () => apiMock)

describe('ImageUpload 封面上传', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })
  afterEach(() => {
    vi.restoreAllMocks()
  })

  function mountUpload(props: Record<string, unknown> = {}) {
    return mount(ImageUpload, {
      props: { modelValue: null, ...props },
    })
  }

  /** 模拟文件选择（change 事件携带文件） */
  async function pickFile(wrapper: ReturnType<typeof mountUpload>, file: File | null) {
    Object.defineProperty(wrapper.find('[data-testid="upload-input"]').element, 'files', {
      value: file ? [file] : [],
      configurable: true,
    })
    await wrapper.find('[data-testid="upload-input"]').trigger('change')
  }

  it('空态：虚线投递区 + 引导文案 + 白名单提示 + button 角色键盘可达', () => {
    const wrapper = mountUpload()
    const zone = wrapper.find('[data-testid="upload-dropzone"]')
    expect(zone.attributes('role')).toBe('button')
    expect(zone.attributes('aria-label')).toBe('上传封面图片')
    expect(wrapper.text()).toContain('点击或拖拽上传封面')
    expect(wrapper.text()).toContain('支持 jpg/jpeg/png/webp')
    expect(wrapper.find('[data-testid="upload-preview"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('已有值：img 预览渲染 + 右上角移除钮', () => {
    const wrapper = mountUpload({ modelValue: '/api/v1/public/covers/0/abc.png' })
    expect(wrapper.find('[data-testid="upload-preview"]').attributes('src')).toBe(
      '/api/v1/public/covers/0/abc.png',
    )
    expect(wrapper.find('[data-testid="upload-remove"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('校验失败（类型）：内联红字 + 不发网络请求', async () => {
    const wrapper = mountUpload()
    await pickFile(wrapper, imageFile('cover.gif'))
    await flushPromises()
    expect(wrapper.find('[data-testid="upload-error"]').text()).toContain('仅支持')
    expect(apiMock.apiClient.post).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('校验失败（超限）：内联红字提示上限', async () => {
    const wrapper = mountUpload()
    await pickFile(wrapper, imageFile('cover.png', 6 * 1024 * 1024))
    await flushPromises()
    expect(wrapper.find('[data-testid="upload-error"]').text()).toContain('超过 5MB 上限')
    expect(apiMock.apiClient.post).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('上传成功：multipart 携带 file，回传 data.url', async () => {
    apiMock.apiClient.post.mockResolvedValue({
      data: { objectKey: '0/abc.png', url: '/api/v1/public/covers/0/abc.png' },
    })
    const wrapper = mountUpload()
    await pickFile(wrapper, imageFile('cover.png'))
    await flushPromises()

    expect(apiMock.apiClient.post).toHaveBeenCalledWith(
      '/admin/courses/cover',
      expect.any(FormData),
    )
    expect(wrapper.emitted('update:modelValue')?.[0]?.[0]).toBe('/api/v1/public/covers/0/abc.png')
    expect(wrapper.find('[data-testid="upload-error"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('上传失败：内联错误 + 重试按钮重传同一文件', async () => {
    apiMock.apiClient.post.mockRejectedValueOnce(new apiMock.ApiError(503, '服务暂时不可用'))
    const wrapper = mountUpload()
    await pickFile(wrapper, imageFile('cover.png'))
    await flushPromises()

    expect(wrapper.find('[data-testid="upload-error"]').text()).toContain('服务暂时不可用')
    expect(wrapper.emitted('error')?.[0]?.[0]).toBe('服务暂时不可用')
    expect(wrapper.find('[data-testid="upload-retry"]').exists()).toBe(true)

    // 重试成功：回传 url
    apiMock.apiClient.post.mockResolvedValueOnce({
      data: { objectKey: '0/abc.png', url: '/api/v1/public/covers/0/abc.png' },
    })
    await wrapper.find('[data-testid="upload-retry"]').trigger('click')
    await flushPromises()
    expect(apiMock.apiClient.post).toHaveBeenCalledTimes(2)
    expect(wrapper.emitted('update:modelValue')?.[0]?.[0]).toBe('/api/v1/public/covers/0/abc.png')
    wrapper.unmount()
  })

  it('上传中：不确定进度遮罩在场，期间禁止重复选择', async () => {
    let release!: (value: { data: { url: string } }) => void
    apiMock.apiClient.post.mockReturnValue(
      new Promise((resolve) => {
        release = resolve
      }),
    )
    const wrapper = mountUpload()
    await pickFile(wrapper, imageFile('cover.png'))
    await flushPromises()

    expect(wrapper.find('[data-testid="upload-progress"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('上传中')

    // 上传中再次选择文件：被吞掉（不重复发请求）
    await pickFile(wrapper, imageFile('another.png'))
    expect(apiMock.apiClient.post).toHaveBeenCalledTimes(1)

    release({ data: { url: '/api/v1/public/covers/0/xyz.png' } })
    await flushPromises()
    expect(wrapper.find('[data-testid="upload-progress"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('删除：confirm 确认后回传 null（仅清表单值）', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const wrapper = mountUpload({ modelValue: '/api/v1/public/covers/0/abc.png' })
    await wrapper.find('[data-testid="upload-remove"]').trigger('click')
    expect(window.confirm).toHaveBeenCalledWith('确认移除封面图片？')
    expect(wrapper.emitted('update:modelValue')?.[0]?.[0]).toBeNull()
    wrapper.unmount()
  })

  it('删除取消：confirm 拒绝不回传', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    const wrapper = mountUpload({ modelValue: '/api/v1/public/covers/0/abc.png' })
    await wrapper.find('[data-testid="upload-remove"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    wrapper.unmount()
  })

  it('拖拽上传：drop 取首文件走同一上传链路', async () => {
    apiMock.apiClient.post.mockResolvedValue({
      data: { objectKey: '0/abc.png', url: '/api/v1/public/covers/0/abc.png' },
    })
    const wrapper = mountUpload()
    const zone = wrapper.find('[data-testid="upload-dropzone"]')
    await zone.trigger('drop', {
      dataTransfer: { files: [imageFile('cover.png')] },
    })
    await flushPromises()
    expect(apiMock.apiClient.post).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('update:modelValue')?.[0]?.[0]).toBe('/api/v1/public/covers/0/abc.png')
    wrapper.unmount()
  })
})
