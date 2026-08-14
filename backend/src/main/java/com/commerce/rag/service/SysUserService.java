package com.commerce.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.auth.DeviceKickService;
import com.commerce.rag.controller.dto.CreateUserRequest;
import com.commerce.rag.controller.dto.UpdateUserRequest;
import com.commerce.rag.controller.dto.UserDTO;
import com.commerce.rag.entity.CourseTeacher;
import com.commerce.rag.entity.SysUser;
import com.commerce.rag.enums.UserRole;
import com.commerce.rag.mapper.CourseTeacherMapper;
import com.commerce.rag.mapper.SysUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 系统用户服务 —— 用户管理 CRUD
 *
 * <p>权限规则：
 * <ul>
 *   <li>超管不可删/不可禁用</li>
 *   <li>教师只能操作自己创建的学生</li>
 *   <li>软删除 + 级联（sys_login_record → REVOKED, jti → blacklist）</li>
 * </ul>
 *
 * @author commerce-rag
 */
@Service
public class SysUserService {

    private static final Logger log = LoggerFactory.getLogger(SysUserService.class);

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final DeviceKickService deviceKickService;
    private final CourseTeacherMapper courseTeacherMapper;

    public SysUserService(
            SysUserMapper userMapper,
            PasswordEncoder passwordEncoder,
            DeviceKickService deviceKickService,
            CourseTeacherMapper courseTeacherMapper) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.deviceKickService = deviceKickService;
        this.courseTeacherMapper = courseTeacherMapper;
    }

    /**
     * 创建用户
     *
     * @param request      创建请求（role 仅允许 SUPER_ADMIN / TEACHER / STUDENT）
     * @param createdBy    创建者用户 ID（用于归属记录）
     * @param operatorRole 操作者角色（TEACHER 只能创建 STUDENT 账号）
     * @return 用户 DTO
     */
    public UserDTO create(CreateUserRequest request, Long createdBy, String operatorRole) {
        // 教师只能创建学生账号（P0-2e：防止教师创建 TEACHER 扩权）
        if (UserRole.TEACHER.name().equals(operatorRole)
                && !UserRole.STUDENT.name().equals(request.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "教师只能创建学生账号");
        }

        // 用户名唯一性校验
        LambdaQueryWrapper<SysUser> wrapper =
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, request.username());
        Long existing = userMapper.selectCount(wrapper);
        if (existing != null && existing > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
        }

        // 超管唯一性校验（全局仅 1 个超管）
        if (UserRole.SUPER_ADMIN.name().equals(request.role())) {
            LambdaQueryWrapper<SysUser> adminWrapper =
                    new LambdaQueryWrapper<SysUser>().eq(SysUser::getRole, UserRole.SUPER_ADMIN.name());
            Long adminCount = userMapper.selectCount(adminWrapper);
            if (adminCount != null && adminCount > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "超级管理员已存在，全局仅允许 1 个");
            }
        }

        SysUser user = new SysUser();
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(request.displayName());
        user.setRole(request.role());
        user.setStatus("ACTIVE");
        user.setCreatedBy(createdBy);
        userMapper.insert(user);

        log.info(
                "创建用户: userId={}, username={}, role={}, createdBy={}",
                user.getId(),
                user.getUsername(),
                user.getRole(),
                createdBy);
        return toDTO(user);
    }

    /**
     * 根据 ID 查询用户
     */
    public UserDTO findById(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        }
        return toDTO(user);
    }

    /**
     * 根据 ID 查询用户（管理端，带教师归属过滤）
     *
     * @param id            目标用户 ID
     * @param currentUserId 当前操作者 ID
     * @param operatorRole  操作者角色
     * @return 用户 DTO；用户不存在或教师无归属权时返回 null（由 controller 统一映射 404）
     */
    public UserDTO findById(Long id, Long currentUserId, String operatorRole) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            return null;
        }
        // 教师只能查看自己创建的学生（P0-2f）
        if (UserRole.TEACHER.name().equals(operatorRole)
                && (user.getCreatedBy() == null || !user.getCreatedBy().equals(currentUserId))) {
            return null;
        }
        return toDTO(user);
    }

    /**
     * 根据用户名查询用户（含密码，用于登录验证）
     */
    public SysUser findByUsername(String username) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username);
        return userMapper.selectOne(wrapper);
    }

    /**
     * 分页查询用户
     *
     * @param page          页码（1-based）
     * @param size          每页条数
     * @param role          角色筛选（可空）
     * @param status        状态筛选（可空）
     * @param currentUserId 当前操作者 ID（教师过滤用）
     * @param operatorRole  操作者角色（TEACHER 仅查自己创建的用户）
     * @return 分页结果
     */
    public IPage<UserDTO> findPage(
            int page, int size, String role, String status, Long currentUserId, String operatorRole) {
        Page<SysUser> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>().orderByDesc(SysUser::getCreatedAt);
        if (role != null && !role.isEmpty()) {
            wrapper.eq(SysUser::getRole, role);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(SysUser::getStatus, status);
        }
        // 教师只能查看自己创建的用户（P0-2f）
        if (UserRole.TEACHER.name().equals(operatorRole)) {
            wrapper.eq(SysUser::getCreatedBy, currentUserId);
        }

        IPage<SysUser> userPage = userMapper.selectPage(pageObj, wrapper);
        // 转换为 DTO
        return userPage.convert(this::toDTO);
    }

    /**
     * 更新用户信息（超管不可改角色）
     *
     * @param id         用户 ID
     * @param request    更新请求
     * @param currentUserId 当前操作者 ID
     */
    public UserDTO update(Long id, UpdateUserRequest request, Long currentUserId) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        }

        // 教师只能操作自己创建的学生
        checkTeacherPermission(user, currentUserId);

        if (request.displayName() != null) {
            user.setDisplayName(request.displayName());
        }
        userMapper.updateById(user);

        log.info("更新用户: userId={}, operator={}", id, currentUserId);
        return toDTO(user);
    }

    /**
     * 重置密码
     *
     * @param id           用户 ID
     * @param newPassword  新密码（明文）
     * @param currentUserId 当前操作者 ID
     */
    public void resetPassword(Long id, String newPassword, Long currentUserId) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        }

        // 超管可重置任何用户密码，教师只能重置自己创建的学生
        checkTeacherPermission(user, currentUserId);

        LambdaUpdateWrapper<SysUser> wrapper = new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, id)
                .set(SysUser::getPasswordHash, passwordEncoder.encode(newPassword));
        userMapper.update(null, wrapper);

        log.info("重置密码: userId={}, operator={}", id, currentUserId);
    }

    /**
     * 更新用户状态（启用/禁用）
     *
     * <p>超管不可禁用。禁用用户时将其所有活跃 session jti 入黑名单。
     *
     * @param id           用户 ID
     * @param status       新状态（ACTIVE / DISABLED）
     * @param currentUserId 当前操作者 ID
     */
    public void updateStatus(Long id, String status, Long currentUserId) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        }

        // 超管不可禁用
        if (UserRole.SUPER_ADMIN.name().equals(user.getRole()) && "DISABLED".equals(status)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "超级管理员不可禁用");
        }

        // 教师只能操作自己创建的学生
        checkTeacherPermission(user, currentUserId);

        LambdaUpdateWrapper<SysUser> wrapper =
                new LambdaUpdateWrapper<SysUser>().eq(SysUser::getId, id).set(SysUser::getStatus, status);
        userMapper.update(null, wrapper);

        // 禁用用户时，将其所有活跃 session jti 入黑名单
        if ("DISABLED".equals(status)) {
            deviceKickService.disableUser(id, currentUserId);
        }

        log.info("更新用户状态: userId={}, status={}, operator={}", id, status, currentUserId);
    }

    /**
     * 删除用户（软删除 + 级联）
     *
     * <p>超管不可删。级联：sys_login_record → REVOKED, jti → blacklist。
     *
     * @param id            用户 ID
     * @param currentUserId 当前操作者 ID
     */
    public void delete(Long id, Long currentUserId) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        }

        // 超管不可删
        if (UserRole.SUPER_ADMIN.name().equals(user.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "超级管理员不可删除");
        }

        // 教师只能操作自己创建的学生
        checkTeacherPermission(user, currentUserId);

        // 软删除用户
        LambdaUpdateWrapper<SysUser> wrapper = new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, id)
                .set(SysUser::getDeleted, System.currentTimeMillis());
        userMapper.update(null, wrapper);

        // 级联软删 course_teacher（教师删除时，课程保留由超管重分配）
        if (UserRole.TEACHER.name().equals(user.getRole())) {
            LambdaUpdateWrapper<CourseTeacher> ctWrapper = new LambdaUpdateWrapper<CourseTeacher>()
                    .eq(CourseTeacher::getTeacherId, id)
                    .eq(CourseTeacher::getDeleted, 0)
                    .set(CourseTeacher::getDeleted, System.currentTimeMillis());
            courseTeacherMapper.update(null, ctWrapper);
        }

        // 禁用该用户所有活跃 session
        deviceKickService.disableUser(id, currentUserId);

        log.info("删除用户: userId={}, operator={}", id, currentUserId);
    }

    /**
     * 教师权限校验：教师只能操作自己创建的学生
     */
    private void checkTeacherPermission(SysUser targetUser, Long currentUserId) {
        SysUser operator = userMapper.selectById(currentUserId);
        if (operator == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "操作者不存在");
        }

        // 超管可操作任何用户
        if (UserRole.SUPER_ADMIN.name().equals(operator.getRole())) {
            return;
        }

        // 教师只能操作学生
        if (UserRole.TEACHER.name().equals(operator.getRole())) {
            if (!UserRole.STUDENT.name().equals(targetUser.getRole())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "教师只能操作学生");
            }
            // 教师只能操作自己创建的学生（P0-2d：created_by 归属校验）
            if (targetUser.getCreatedBy() == null || !targetUser.getCreatedBy().equals(currentUserId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "教师只能操作自己创建的学生");
            }
            return;
        }
        // 非超管/教师角色一律拒绝（fail-closed：角色变更后旧 token 不得获得操作权）
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作");
    }

    /**
     * Entity → DTO 转换
     */
    private UserDTO toDTO(SysUser user) {
        return new UserDTO(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole(),
                user.getStatus(),
                user.getCreatedAt());
    }
}
