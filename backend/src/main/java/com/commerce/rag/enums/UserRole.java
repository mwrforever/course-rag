package com.commerce.rag.enums;

/**
 * 用户角色枚举 —— 三层角色体系
 *
 * <p>SUPER_ADMIN: 超级管理员（仅 1 个，不可删/不可禁用），全权限。
 * <p>TEACHER: 教师，管理自己创建的资源。
 * <p>STUDENT: 学生，仅查询自己有权的资源。
 *
 * @author commerce-rag
 */
public enum UserRole {
    SUPER_ADMIN,
    TEACHER,
    STUDENT
}
