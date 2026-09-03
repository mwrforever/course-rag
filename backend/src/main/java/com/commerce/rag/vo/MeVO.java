package com.commerce.rag.vo;

/**
 * 当前登录用户身份视图（M10 /auth/me 端点响应；无副作用端点，不旋转 RT、不写库）
 *
 * @param userId      用户 ID（雪花 Long，全局 Jackson Long→String 序列化输出 string）
 * @param role        角色（STUDENT/TEACHER/SUPER_ADMIN）
 * @param displayName 显示昵称（顶栏/导航恢复用）
 */
public record MeVO(Long userId, String role, String displayName) {}
