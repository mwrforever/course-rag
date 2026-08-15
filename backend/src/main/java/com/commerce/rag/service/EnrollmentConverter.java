package com.commerce.rag.service;

import com.commerce.rag.dto.StudentDTO;
import com.commerce.rag.entity.CourseEnrollment;
import com.commerce.rag.entity.SysUser;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * 选课学生转换器 —— SysUser + CourseEnrollment → StudentDTO
 *
 * <p>多源映射：id/username/displayName 来自用户，enrolledAt/status 来自选课记录。
 *
 * @author commerce-rag
 */
@Mapper(componentModel = "spring")
public interface EnrollmentConverter {

    /** 用户实体 + 选课记录 → 学生 DTO */
    @Mapping(target = "id", source = "user.id")
    @Mapping(target = "username", source = "user.username")
    @Mapping(target = "displayName", source = "user.displayName")
    @Mapping(target = "enrolledAt", source = "enrollment.enrolledAt")
    @Mapping(target = "status", source = "enrollment.status")
    StudentDTO toDTO(SysUser user, CourseEnrollment enrollment);
}
