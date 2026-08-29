package com.commerce.rag.vo;

/**
 * 封面上传结果视图对象 —— controller 出参（B 端接口 POST /api/v1/admin/courses/cover）
 *
 * <p>契约 D.2.2 响应 VO：B 端表单将 url 整串写入 coverImage 字段随课程创建/更新提交；
 * 历史外部绝对 URL 与新相对 URL 均为合法 img src，天然向后兼容。
 *
 * @param objectKey MinIO 对象键（形如 {@code 0/{uuid32}.{ext}}，uuid 服务端预生成不可猜测）
 * @param url       封面公开访问相对路径（{@code /api/v1/public/covers/{objectKey}}，
 *                  双前端 dev 代理同源转发 /api/v1，img 标签直用）
 */
public record CourseCoverVO(String objectKey, String url) {}
