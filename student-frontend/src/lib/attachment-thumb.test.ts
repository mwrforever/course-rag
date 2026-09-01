/**
 * 图片附件缩略图生成测试（PERF-18）
 *
 * jsdom 无 createImageBitmap 与 canvas 2d，全链路打桩：
 * - 成功路径：createImageBitmap 收到缩放参数（resizeWidth 96 + EXIF 方向保留）、
 *   canvas 绘制后 toBlob('image/jpeg')、返回 objectURL、位图显式 close
 * - 降级路径：createImageBitmap 缺失 / 解码失败 / 小图不放大 / toBlob null →
 *   一律返回 null 由调用方原图兜底
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createAttachmentThumbUrl } from "./attachment-thumb";

/** 假位图（宽 400 高 266 模拟横幅照片；close 调用可断言） */
function makeBitmap(width = 400, height = 266) {
  return { width, height, close: vi.fn() };
}

/** 假 canvas 2d 上下文（绘制调用可断言） */
const fakeCtx = {
  fillStyle: "",
  fillRect: vi.fn(),
  drawImage: vi.fn(),
} as unknown as CanvasRenderingContext2D;

const createObjectURLMock = vi.fn();
/** getContext 桩（断言画布尺寸用：mock.contexts[0] 即首次调用的 canvas 实例） */
let getContextSpy: ReturnType<typeof vi.spyOn> | null = null;
/** toBlob 桩（断言编码参数用） */
let toBlobSpy: ReturnType<typeof vi.spyOn> | null = null;

beforeEach(() => {
  // canvas 2d / toBlob 打桩（jsdom 无 canvas 包，原生实现缺失）
  getContextSpy = vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(fakeCtx);
  toBlobSpy = vi.spyOn(HTMLCanvasElement.prototype, "toBlob").mockImplementation(function (
    this: HTMLCanvasElement,
    callback: BlobCallback,
  ) {
    callback(new Blob(["thumb"]));
  } as HTMLCanvasElement["toBlob"]);
  // URL.createObjectURL 打桩（jsdom 未实现，同 page.test 口径）
  Object.defineProperty(URL, "createObjectURL", {
    value: createObjectURLMock,
    configurable: true,
  });
  createObjectURLMock.mockReset().mockReturnValue("blob:thumb-url");
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  delete (URL as unknown as Record<string, unknown>).createObjectURL;
  getContextSpy = null;
  toBlobSpy = null;
});

describe("createAttachmentThumbUrl 成功路径", () => {
  it("createImageBitmap 携带缩放与 EXIF 方向参数，canvas 绘制编码后返回缩略 URL 并释放位图", async () => {
    const bitmap = makeBitmap();
    const createImageBitmapMock = vi.fn().mockResolvedValue(bitmap);
    vi.stubGlobal("createImageBitmap", createImageBitmapMock);
    const file = new File(["x"], "照片.jpg", { type: "image/jpeg" });

    await expect(createAttachmentThumbUrl(file)).resolves.toBe("blob:thumb-url");

    // 缩放参数契约：96px 宽等比缩 + 'from-image' 保留 EXIF 方向（竖拍不横躺）
    expect(createImageBitmapMock).toHaveBeenCalledWith(file, {
      resizeWidth: 96,
      imageOrientation: "from-image",
    });
    // 白底填充 + 位图绘制均执行（透明 PNG 编码 JPEG 不发黑）
    expect(fakeCtx.fillRect).toHaveBeenCalled();
    expect(fakeCtx.drawImage).toHaveBeenCalled();
    // 画布尺寸 = 缩放后位图尺寸；toBlob 以 JPEG 编码（小尺寸视觉无损档质量由实现定值）
    const canvas = getContextSpy?.mock.contexts[0] as HTMLCanvasElement | undefined;
    expect(canvas?.width).toBe(400);
    expect(canvas?.height).toBe(266);
    expect(toBlobSpy).toHaveBeenCalledWith(expect.any(Function), "image/jpeg", 0.85);
    // 位图显式 close（ImageBitmap 内存及时释放）
    expect(bitmap.close).toHaveBeenCalledTimes(1);
    expect(createObjectURLMock).toHaveBeenCalledTimes(1);
  });
});

describe("createAttachmentThumbUrl 降级路径（返回 null 由调用方原图兜底）", () => {
  it("环境无 createImageBitmap（老浏览器/jsdom）：直接返回 null", async () => {
    vi.stubGlobal("createImageBitmap", undefined);
    const file = new File(["x"], "照片.jpg", { type: "image/jpeg" });
    await expect(createAttachmentThumbUrl(file)).resolves.toBeNull();
    expect(createObjectURLMock).not.toHaveBeenCalled();
  });

  it("createImageBitmap 解码失败（坏文件）：返回 null", async () => {
    vi.stubGlobal("createImageBitmap", vi.fn().mockRejectedValue(new Error("decode error")));
    const file = new File(["x"], "坏图.jpg", { type: "image/jpeg" });
    await expect(createAttachmentThumbUrl(file)).resolves.toBeNull();
  });

  it("小图（宽 ≤96）不放大：返回 null 用原图", async () => {
    vi.stubGlobal("createImageBitmap", vi.fn().mockResolvedValue(makeBitmap(80, 53)));
    const file = new File(["x"], "小图.png", { type: "image/png" });
    await expect(createAttachmentThumbUrl(file)).resolves.toBeNull();
    expect(createObjectURLMock).not.toHaveBeenCalled();
  });

  it("toBlob 回调 null（编码失败）：返回 null", async () => {
    vi.stubGlobal("createImageBitmap", vi.fn().mockResolvedValue(makeBitmap()));
    vi.spyOn(HTMLCanvasElement.prototype, "toBlob").mockImplementation(function (
      this: HTMLCanvasElement,
      callback: BlobCallback,
    ) {
      callback(null);
    } as HTMLCanvasElement["toBlob"]);
    const file = new File(["x"], "照片.jpg", { type: "image/jpeg" });
    await expect(createAttachmentThumbUrl(file)).resolves.toBeNull();
  });
});
