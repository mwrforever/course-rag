package com.commerce.rag.vo;

/**
 * 课程购买结果视图对象 —— controller 出参（C 端接口 POST /api/v1/student/courses/{courseId}/purchase）
 *
 * <p>契约 B.2 响应 VO：重复购买幂等返回与首次相同的成功结构（不报 409、不重复插行）。
 *
 * @param courseId  课程 ID（回显请求的课程 ID）
 * @param status    购买后选课状态（恒 ACTIVE——已 ACTIVE 直接返回 / DROPPED 重激活 / 新插入均为 ACTIVE）
 * @param purchased 是否已购（恒 true；保留字段以支撑未来支付态扩展，如支付中/待审批）
 */
public record CoursePurchaseVO(Long courseId, String status, boolean purchased) {}
