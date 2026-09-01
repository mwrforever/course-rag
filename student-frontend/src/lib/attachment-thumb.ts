/**
 * 图片附件缩略图生成（PERF-18）
 *
 * 职责：为图片附件异步生成小尺寸缩略 blob URL，供 chips（36px）与消息行（28px）
 * 缩略渲染——相机原图（上限 10MB）不再整图驻留内存并全量解码后缩小绘制，
 * 预览弹窗始终使用原图 blob URL 不受本模块影响。
 *
 * 实现路径（浏览器兼容稳妥链）：createImageBitmap（resizeWidth 等比缩到 96px 宽、
 * imageOrientation 'from-image' 保留 EXIF 方向）→ canvas 2d 绘制（白底填充：
 * PNG/WebP 透明区编码 JPEG 不发黑）→ canvas.toBlob('image/jpeg') →
 * URL.createObjectURL。
 *
 * 降级契约：createImageBitmap 不可用（老浏览器/jsdom 测试环境）、解码失败、
 * 图像本身不大于缩略宽、canvas 2d 不可用、toBlob 失败——任何一步返回 null，
 * 调用方以原图 blob URL 兜底（瞬时占位，无失败感知）。
 */

/** 缩略图目标宽度（像素）：chips 36px / 消息行 28px 的 2x+ DPR 余量 */
const THUMB_WIDTH = 96;

/** 缩略图 JPEG 质量（96px 小尺寸视觉无损档） */
const THUMB_QUALITY = 0.85;

/**
 * 生成图片附件缩略 blob URL
 *
 * @param file 图片附件原始文件（白名单内的图片类型；文档类不适用，调用方先行分流）
 * @returns 缩略 blob URL；生成失败或环境不支持时返回 null（调用方原图兜底）
 */
export async function createAttachmentThumbUrl(file: File): Promise<string | null> {
  // 环境守卫：无 createImageBitmap（老浏览器/jsdom）直接降级，不抛错不阻塞上传链路
  if (typeof createImageBitmap !== "function") {
    return null;
  }
  let bitmap: ImageBitmap;
  try {
    // EXIF 方向必须保留：'from-image' 按图片自带方向标记摆正后再缩放
    bitmap = await createImageBitmap(file, {
      resizeWidth: THUMB_WIDTH,
      imageOrientation: "from-image",
    });
  } catch {
    // 解码失败（坏文件/格式不支持）：原图兜底
    return null;
  }
  try {
    // 小图不放大（原图本体已足够小，直接用原图更优）
    if (bitmap.width <= THUMB_WIDTH) {
      return null;
    }
    const canvas = document.createElement("canvas");
    canvas.width = bitmap.width;
    canvas.height = bitmap.height;
    const ctx = canvas.getContext("2d");
    // jsdom 等无 2d 上下文环境：降级
    if (!ctx) {
      return null;
    }
    // 白底填充后绘制位图（透明区编码 JPEG 不发黑）
    ctx.fillStyle = "#ffffff";
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.drawImage(bitmap, 0, 0);
    const blob = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob(resolve, "image/jpeg", THUMB_QUALITY),
    );
    if (!blob) {
      return null;
    }
    return URL.createObjectURL(blob);
  } catch {
    // canvas/toBlob 阶段异常（极防御）：原图兜底
    return null;
  } finally {
    // 显式释放位图内存（ImageBitmap 不再引用后 GC 及时性无保证）
    bitmap.close();
  }
}
